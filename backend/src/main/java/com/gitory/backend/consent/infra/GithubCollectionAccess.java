package com.gitory.backend.consent.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubCollectionTarget;
import com.gitory.backend.consent.port.GithubCountTarget;
import com.gitory.backend.consent.port.GithubRepositoryCount;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** consent 안에서 연결 상태를 확인하고, 복호화한 토큰은 HTTP 헤더에만 쓴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCollectionAccess implements GithubCollectionAccessPort {

    // GitHub GraphQL 은 저장소 100개를 한 번에 세면 시간 초과(502)가 나서 나눠 묻는다
    private static final int COUNT_BATCH_SIZE = 20;

    // GitHub 은 같은 토큰의 동시 요청이 많으면 2차 한도로 막으므로 묶음은 3개까지만 동시에 묻는다
    private static final int COUNT_CONCURRENCY = 3;

    // GitHub 은 GraphQL 처리 시간이 60초 동안 60초를 넘으면 거절하므로, 조회 한 번에 세는 묶음 수를 막고 나머지는 다음 조회로 미룬다
    private static final int COUNT_BATCH_LIMIT = 15;

    private final UserRepository users;
    private final GithubConnectionRepository connections;
    private final TokenCipher cipher;
    private final AiHttpClient ai;
    private final GithubRepositoryClient github;
    private final GithubCountClient counter;

    @Override
    public JsonNode collect(Long userId, GithubCollectionTarget target) {

        User user = activeUser(userId).orElseThrow(GithubCollectionAccess::unavailable);
        String token = activeToken(userId).orElseThrow(GithubCollectionAccess::unavailable);
        AiCollectRequest request = new AiCollectRequest(target.userRepositoryId(), target.repository(),
                new AiCollectActor(user.getGithubLogin(), List.of()), target.branches());

        return ai.collect(request, token);

    }

    @Override
    public List<GithubRepositoryResponse> repositories(Long userId) {

        activeUser(userId).orElseThrow(GithubNotConnectedException::new);
        String token = activeToken(userId).orElseThrow(GithubNotConnectedException::new);

        try {
            return github.fetchRepositories(token);
        } catch (HttpClientErrorException.Unauthorized rejected) {
            throw new GithubNotConnectedException();
        }

    }

    @Override
    public List<GithubRepositoryCount> countActivity(Long userId, List<GithubCountTarget> targets) {

        User user = activeUser(userId).orElseThrow(GithubNotConnectedException::new);
        String token = activeToken(userId).orElseThrow(GithubNotConnectedException::new);

        String login = user.getGithubLogin();
        String viewerId = counter.viewerId(token);
        // 내 PR 에 달린 리뷰 댓글에 답글만 달아도 GitHub 이 내 리뷰로 기록해 내 PR 이 섞이므로 내가 쓴 PR 은 뺀다
        String reviewedQuery = "is:pr reviewed-by:" + login + " -author:" + login;
        GithubSearchCounts searches = new GithubSearchCounts(
                counter.countPullRequests(token, "is:pr author:" + login),
                counter.countPullRequests(token, reviewedQuery));

        int countable = Math.min(targets.size(), COUNT_BATCH_SIZE * COUNT_BATCH_LIMIT);
        if (countable < targets.size()) {
            log.info("저장소 {}개 중 {}개만 세고 나머지는 다음 조회 때 센다", targets.size(), countable);
        }

        List<GithubRepositoryCount> counts = new ArrayList<>();
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicInteger skipped = new AtomicInteger();
        // 실행기를 요청마다 만들어 다른 사용자의 개수 세기를 기다리지 않게 하고, 응답 전에 닫아 스레드를 남기지 않는다
        try (ExecutorService pool = Executors.newFixedThreadPool(COUNT_CONCURRENCY)) {
            List<CompletableFuture<List<GithubRepositoryCount>>> batchCounts = new ArrayList<>();
            for (int from = 0; from < countable; from += COUNT_BATCH_SIZE) {
                List<GithubCountTarget> batch = targets.subList(from, Math.min(from + COUNT_BATCH_SIZE, countable));
                batchCounts.add(CompletableFuture.supplyAsync(
                        () -> countBatch(token, viewerId, batch, searches, rejected, skipped), pool));
            }
            batchCounts.forEach(batchCount -> counts.addAll(batchCount.join()));
        }

        if (rejected.get()) {
            log.warn("GitHub 이 개수 세기를 한도로 거절해 저장소 {}개는 다음 조회 때 센다", skipped.get());
        }

        return counts;

    }

    /** 한 묶음이 실패해도 나머지 묶음은 세지만, GitHub 이 한도로 거절하면 아직 보내지 않은 묶음은 보내지 않는다 */
    private List<GithubRepositoryCount> countBatch(String token, String viewerId, List<GithubCountTarget> batch,
                                                   GithubSearchCounts searches, AtomicBoolean rejected,
                                                   AtomicInteger skipped) {

        if (rejected.get()) {
            skipped.addAndGet(batch.size());
            return List.of();
        }

        Map<Long, GithubRepositoryTotals> totals;
        try {
            totals = counter.countCommits(token, viewerId, batch);
        } catch (RuntimeException failed) {
            if (GithubCountClient.isRateLimited(failed)) {
                rejected.set(true);
                skipped.addAndGet(batch.size());
                log.warn("GitHub 이 저장소 {}개의 개수 세기를 한도로 거절했다", batch.size(), failed);
                return List.of();
            }
            log.warn("GitHub 저장소 {}개의 개수를 세지 못했다", batch.size(), failed);
            return List.of();
        }

        List<GithubRepositoryCount> counts = new ArrayList<>();
        for (GithubCountTarget target : batch) {
            GithubRepositoryTotals total = totals.get(target.githubRepoId());
            if (total != null) {
                counts.add(countOf(target, total, searches));
            }
        }

        return counts;

    }

    /** 검색과 GraphQL 은 반영 시점이 달라, 검색한 내 PR 수가 GraphQL 의 전체 PR 수를 넘으면 전체 PR 수에 맞춘다 */
    private static GithubRepositoryCount countOf(GithubCountTarget target, GithubRepositoryTotals total,
                                                 GithubSearchCounts searches) {

        String key = GithubCountClient.nameKey(target.owner(), target.name());
        // 머지 커밋은 머지 버튼을 누른 사람 것으로 세져 내 몫을 부풀리므로 내 것만 뺀다, 남의 머지·봇 커밋은 팀 쪽이라 내 몫을 키우지 않는다
        int ownMerges = total.ownMergeCommitCount();
        int ownPullRequests = Math.min(searches.authoredPullRequests().getOrDefault(key, 0), total.pullRequestCount());

        return new GithubRepositoryCount(target.githubRepoId(), total.commitCount() - ownMerges,
                total.ownCommitCount() - ownMerges, total.pullRequestCount(), ownPullRequests,
                searches.reviewedPullRequests().getOrDefault(key, 0));

    }

    private Optional<User> activeUser(Long userId) {

        return users.findById(userId).filter(u -> !u.isDeletionRequested());

    }

    private Optional<String> activeToken(Long userId) {

        return connections.findByUserIdAndRevokedAtIsNull(userId)
                .filter(GithubConnection::isActive)
                .filter(c -> c.getTokenExpiresAt() == null || c.getTokenExpiresAt().isAfter(Instant.now()))
                .map(this::decryptOrNull);

    }

    /** 암호문·키·외부 암호화 예외를 로그로 넘기지 않도록 복호화 실패는 연결이 없는 것과 같이 다룬다 */
    private String decryptOrNull(GithubConnection connection) {

        try {
            return cipher.decrypt(connection.getTokenEnc());
        } catch (RuntimeException failed) {
            return null;
        }

    }

    private static AiClientException unavailable() {
        return new AiClientException("GITHUB_CONNECTION_UNAVAILABLE", false, null);
    }
}
