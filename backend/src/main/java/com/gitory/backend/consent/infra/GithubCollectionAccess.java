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

/** consent 안에서 연결 상태를 확인하고, 복호화한 토큰은 HTTP 헤더에만 쓴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubCollectionAccess implements GithubCollectionAccessPort {

    // GitHub GraphQL 은 저장소 100개를 한 번에 세면 시간 초과(502)가 나서 나눠 묻는다
    private static final int COUNT_BATCH_SIZE = 20;

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

        String viewerId = counter.viewerId(token);
        Map<String, Integer> authored = counter.countPullRequests(token, "is:pr author:" + user.getGithubLogin());
        Map<String, Integer> reviewed = counter.countPullRequests(token, "is:pr reviewed-by:" + user.getGithubLogin());

        List<GithubRepositoryCount> counts = new ArrayList<>();
        for (int from = 0; from < targets.size(); from += COUNT_BATCH_SIZE) {
            List<GithubCountTarget> batch = targets.subList(from, Math.min(from + COUNT_BATCH_SIZE, targets.size()));
            counts.addAll(countBatch(token, viewerId, batch, authored, reviewed));
        }

        return counts;

    }

    /** 한 묶음이 실패해도 나머지 묶음은 센다 */
    private List<GithubRepositoryCount> countBatch(String token, String viewerId, List<GithubCountTarget> batch,
                                                   Map<String, Integer> authored, Map<String, Integer> reviewed) {

        Map<Long, GithubRepositoryTotals> totals;
        try {
            totals = counter.countCommits(token, viewerId, batch);
        } catch (RuntimeException failed) {
            log.warn("GitHub 저장소 {}개의 개수를 세지 못했다", batch.size(), failed);
            return List.of();
        }

        List<GithubRepositoryCount> counts = new ArrayList<>();
        for (GithubCountTarget target : batch) {
            GithubRepositoryTotals total = totals.get(target.githubRepoId());
            if (total != null) {
                counts.add(countOf(target, total, authored, reviewed));
            }
        }

        return counts;

    }

    private static GithubRepositoryCount countOf(GithubCountTarget target, GithubRepositoryTotals total,
                                                 Map<String, Integer> authored, Map<String, Integer> reviewed) {

        String key = GithubCountClient.nameKey(target.owner(), target.name());
        // 검색과 GraphQL 은 반영 시점이 달라 내 PR 이 전체 PR 보다 많게 나올 수 있는데, 그러면 DB 규칙(own_pr_count <= pr_count)에 걸린다
        int ownPullRequests = Math.min(authored.getOrDefault(key, 0), total.pullRequestCount());

        return new GithubRepositoryCount(target.githubRepoId(), total.commitCount(), total.ownCommitCount(),
                total.pullRequestCount(), ownPullRequests, reviewed.getOrDefault(key, 0));

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
