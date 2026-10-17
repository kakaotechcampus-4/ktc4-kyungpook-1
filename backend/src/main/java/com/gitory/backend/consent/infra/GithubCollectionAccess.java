package com.gitory.backend.consent.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubDisconnectService;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.GithubRateLimitedException;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.DiffEvidenceResult;
import com.gitory.backend.consent.port.DiffEvidenceTarget;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    // 목록 요청은 프런트 제한 30초 안에 끝나야 하므로 15초 뒤부터는 새 묶음·하나씩 다시 세기·내 커밋 이어 받기·내 커밋 수 요청을 보내지 않는다
    // 시작할 때의 PR 검색 두 번과 사용자 id 요청은 끊지 않고, 이미 보낸 요청은 읽기 제한(10초)까지 걸릴 수 있다
    private static final Duration COUNT_TIME_LIMIT = Duration.ofSeconds(15);

    private static final String DIFF_EVIDENCE_PATH = "/internal/analysis/diff-evidence";

    private final UserRepository users;
    private final GithubConnectionRepository connections;
    private final TokenCipher cipher;
    private final AiHttpClient ai;
    private final GithubRepositoryClient github;
    private final GithubCountClient counter;
    private final Clock clock;
    private final GithubDisconnectService disconnects;
    private final GithubRateLimitClient rateLimit;
    private final AiClientProperties aiProperties;

    @Override
    public JsonNode collect(Long userId, GithubCollectionTarget target, List<String> knownCommitShas) {

        User user = activeUser(userId).orElseThrow(GithubCollectionAccess::unavailable);
        String token = activeToken(userId).orElseThrow(GithubCollectionAccess::unavailable);
        AiCollectRequest request = new AiCollectRequest(target.userRepositoryId(), target.repository(),
                new AiCollectActor(user.getGithubLogin(), List.of()), target.branches(), knownCommitShas);

        return ai.collect(request, token);

    }

    @Override
    public DiffEvidenceResult diffEvidence(Long userId, DiffEvidenceTarget target) {

        activeUser(userId).orElseThrow(GithubCollectionAccess::unavailable);
        String token = activeToken(userId).orElseThrow(GithubCollectionAccess::unavailable);
        AiDiffEvidenceRequest request = new AiDiffEvidenceRequest(target.userRepositoryId(), target.repository(),
                target.commitShas(), target.githubPrNumber(), target.title());

        JsonNode data = ai.postWithGithubToken(DIFF_EVIDENCE_PATH, request, token,
                aiProperties.diffEvidenceReadTimeout());
        return DiffEvidenceReader.read(data, target.userRepositoryId());

    }

    @Override
    public void requireUsableConnection(Long userId) {

        activeUser(userId).orElseThrow(GithubNotConnectedException::new);
        activeToken(userId).orElseThrow(GithubNotConnectedException::new);

    }

    @Override
    public List<GithubRepositoryResponse> repositories(Long userId) {

        activeUser(userId).orElseThrow(GithubNotConnectedException::new);
        UsableToken token = activeUsableToken(userId).orElseThrow(GithubNotConnectedException::new);

        try {
            return github.fetchRepositories(token.value());
        } catch (HttpClientErrorException.Unauthorized rejected) {
            disconnects.revokeRejected(userId, token.encrypted());
            throw new GithubNotConnectedException();
        } catch (HttpClientErrorException failed) {
            if (GithubCountClient.isRateLimited(failed)) {
                throw new GithubRateLimitedException();
            }
            throw failed;
        }

    }

    @Override
    public List<GithubRepositoryCount> countActivity(Long userId, List<GithubCountTarget> targets) {

        Instant deadline = clock.instant().plus(COUNT_TIME_LIMIT);
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
        GithubCountRun run = new GithubCountRun(token, viewerId, searches, deadline, new AtomicReference<>(), new AtomicInteger());
        // 실행기를 요청마다 만들어 다른 사용자의 개수 세기를 기다리지 않게 하고, 응답 전에 닫아 스레드를 남기지 않는다
        try (ExecutorService pool = Executors.newFixedThreadPool(COUNT_CONCURRENCY)) {
            List<CompletableFuture<List<GithubRepositoryCount>>> batchCounts = new ArrayList<>();
            for (int from = 0; from < countable; from += COUNT_BATCH_SIZE) {
                List<GithubCountTarget> batch = targets.subList(from, Math.min(from + COUNT_BATCH_SIZE, countable));
                batchCounts.add(CompletableFuture.supplyAsync(() -> countBatch(run, batch), pool));
            }
            batchCounts.forEach(batchCount -> counts.addAll(batchCount.join()));
        }

        GithubCountStop stopped = run.stopped().get();
        if (stopped != null) {
            log.warn(stopped.message, run.skipped().get());
        }

        return counts;

    }

    /** 한도 조회가 실패해도 분석 결과는 그대로여야 해서 어떤 실패든 빈 값으로 돌려주고, 응답 본문이 로그에 남지 않게 예외 종류만 적는다 */
    @Override
    public Optional<Instant> rateLimitResetAt(Long userId) {

        try {
            Optional<String> token = activeUser(userId).flatMap(user -> activeToken(userId));
            if (token.isEmpty()) {
                log.warn("GitHub 연결이 없어 호출 한도를 조회하지 않았다: 사용자 {}", userId);
                return Optional.empty();
            }

            return rateLimit.resetAt(token.get());
        } catch (RuntimeException failed) {
            log.warn("GitHub 호출 한도를 조회하지 못했다: 사용자 {}, {}", userId, failed.getClass().getSimpleName());
            return Optional.empty();
        }

    }

    /** 한도·토큰 거절이나 시간 제한이면 아직 보내지 않은 묶음은 보내지 않고, 그 밖의 이유로 묶음이 실패하면 저장소 하나씩 다시 세어 문제 저장소만 뺀다 */
    private List<GithubRepositoryCount> countBatch(GithubCountRun run, List<GithubCountTarget> batch) {

        if (stopped(run)) {
            run.skipped().addAndGet(batch.size());
            return List.of();
        }

        Map<Long, GithubRepositoryTotals> totals;
        try {
            totals = counter.countCommits(run.token(), run.viewerId(), batch, () -> stopped(run));
        } catch (RuntimeException failed) {
            if (GithubCountClient.isRateLimited(failed)) {
                run.stopped().compareAndSet(null, GithubCountStop.RATE_LIMITED);
                run.skipped().addAndGet(batch.size());
                log.warn("GitHub 이 저장소 {}개의 개수 세기를 한도로 거절했다", batch.size(), failed);
                return List.of();
            }
            if (GithubCountClient.isUnauthorized(failed)) {
                run.stopped().compareAndSet(null, GithubCountStop.UNAUTHORIZED);
                run.skipped().addAndGet(batch.size());
                log.warn("GitHub 이 토큰을 거절해(401) 저장소 {}개의 개수 세기를 멈춘다", batch.size(), failed);
                return List.of();
            }
            if (batch.size() == 1) {
                GithubCountTarget target = batch.get(0);
                log.warn("GitHub 저장소 {}/{} 의 개수를 세지 못해 다음 조회 때 다시 센다", target.owner(), target.name(), failed);
                return List.of();
            }
            log.warn("GitHub 저장소 {}개를 한 번에 세지 못해 하나씩 다시 센다", batch.size(), failed);
            List<GithubRepositoryCount> recounted = new ArrayList<>();
            for (GithubCountTarget target : batch) {
                recounted.addAll(countBatch(run, List.of(target)));
            }
            return recounted;
        }

        List<GithubRepositoryCount> counts = new ArrayList<>();
        for (GithubCountTarget target : batch) {
            GithubRepositoryTotals total = totals.get(target.githubRepoId());
            if (total != null) {
                counts.add(countOf(target, total, run.searches()));
            }
        }
        // 내 커밋 이어 받기가 멈춰 결과에서 빠진 저장소도 건너뛴 수에 넣어야 마지막 로그가 맞다
        if (run.stopped().get() != null) {
            run.skipped().addAndGet(batch.size() - counts.size());
        }

        return counts;

    }

    private boolean stopped(GithubCountRun run) {

        if (run.stopped().get() == null && !clock.instant().isBefore(run.deadline())) {
            run.stopped().compareAndSet(null, GithubCountStop.TIME_LIMIT);
        }

        return run.stopped().get() != null;

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

        return activeUsableToken(userId).map(UsableToken::value);

    }

    /** 거절받은 토큰이 그사이 재로그인으로 바뀌었는지 가리도록 풀기 전의 암호문을 함께 돌려준다 */
    private Optional<UsableToken> activeUsableToken(Long userId) {

        return connections.findByUserIdAndRevokedAtIsNull(userId)
                .filter(GithubConnection::isActive)
                .filter(c -> c.getTokenExpiresAt() == null || c.getTokenExpiresAt().isAfter(Instant.now()))
                .flatMap(c -> Optional.ofNullable(decryptOrNull(c)).map(value -> new UsableToken(value, c.getTokenEnc())));

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
