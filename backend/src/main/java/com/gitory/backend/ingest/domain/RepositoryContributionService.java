package com.gitory.backend.ingest.domain;

import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubRepositoryCount;
import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 저장소 목록에 보여 줄 기여 개수를 GitHub 에서 세어 user_repository 에 남긴다
 * 개수는 보조 정보라 세지 못해도 목록 조회를 막지 않고, 세지 못한 저장소는 다음 조회 때 다시 센다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryContributionService {

    private final GithubCollectionAccessPort github;
    private final ConnectedRepositoryRepository connections;
    private final TransactionTemplate transaction;

    /** 아직 안 셌거나 센 뒤 새 push 가 있는 저장소만 GitHub 에 묻는다 */
    public void refresh(Long userId, List<Long> githubRepoIds) {

        List<ContributionTarget> targets = connections.findUncounted(userId, githubRepoIds);
        if (targets.isEmpty()) {
            return;
        }

        Instant countedAt = Instant.now();
        try {
            List<GithubRepositoryCount> counts =
                    github.countActivity(userId, targets.stream().map(ContributionTarget::toCountTarget).toList());
            Map<Long, Long> connectionIds = targets.stream()
                    .collect(Collectors.toMap(ContributionTarget::githubRepoId, ContributionTarget::userRepositoryId));
            transaction.executeWithoutResult(status -> counts.forEach(
                    count -> record(connectionIds.get(count.githubRepoId()), count, countedAt)));
        } catch (RuntimeException failed) {
            log.warn("사용자 {} 의 저장소 기여 개수를 세지 못해 다음 조회 때 다시 센다", userId, failed);
        }

    }

    private void record(Long connectionId, GithubRepositoryCount count, Instant countedAt) {

        connections.findById(connectionId).orElseThrow().recordCounts(count.commitCount(), count.ownCommitCount(),
                count.pullRequestCount(), count.ownPullRequestCount(), count.reviewedPullRequestCount(), countedAt);

    }
}
