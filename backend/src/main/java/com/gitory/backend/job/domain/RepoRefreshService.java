package com.gitory.backend.job.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.LastAnalyzedService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.ingest.domain.RepositoryPushedAfterAnalysis;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.job.infra.RepoRefreshRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 정리한 뒤 push 가 있는 레포의 분석 Job 을 사용자 대신 접수하고, 끝난 일 가운데 보지 않은 알림을 읽고 닫는다
 * 주의: JobIntakeService 와 같은 이유로 클래스에 @Transactional 을 붙이지 말 것 — 접수는 동시 요청이 DB 제약으로 갈린다
 */
@Service
@RequiredArgsConstructor
public class RepoRefreshService {

    static final int MAX_PER_CHECK = 3;
    static final Duration COOLDOWN = Duration.ofHours(6);

    private final GithubCollectionAccessPort githubAccess;
    private final LastAnalyzedService analyzed;
    private final AnalysisJobRepository jobs;
    private final RepoRefreshRepository refreshes;
    private final JobIntakeService jobIntake;
    private final RepositoryOwnershipService ownership;
    private final Clock clock;

    /** 접수한 레포 수를 돌려주며, 쓸 수 있는 GitHub 연결이 없으면 오류 없이 0 이다 */
    public int check(Long userId) {

        try {
            githubAccess.requireUsableConnection(userId);
        } catch (GithubNotConnectedException notConnected) {
            return 0;
        }

        Instant now = Instant.now(clock);
        int queued = 0;
        for (RepositoryPushedAfterAnalysis repo : analyzed.pushedAfterAnalysis(userId)) {
            if (queued == MAX_PER_CHECK) {
                break;
            }
            if (isBusy(repo.id(), now)) {
                continue;
            }

            JobIntakeResult result = jobIntake.intake(userId, repo.id(), null);
            if (result.created()) {
                AnalysisJob job = jobs.findByPublicId(result.jobId()).orElseThrow();
                refreshes.save(RepoRefresh.reading(userId, repo.id(), job.getId(), repo.lastAnalyzedAt(), now));
                queued++;
            }
        }

        return queued;

    }

    public List<RepoUpdateRow> updates(Long userId) {

        return refreshes.findUnseenReadyRows(userId);

    }

    /** 남의 레포나 없는 레포면 RepositoryNotFoundException 이다 */
    public void markSeen(UUID repoPublicId, Long userId) {

        Long userRepositoryId = ownership.findOwnedRepository(repoPublicId, userId, AuditAction.VIEW_REPO)
                .orElseThrow(RepositoryNotFoundException::new);
        refreshes.markSeen(userRepositoryId, Instant.now(clock));

    }

    private boolean isBusy(Long userRepositoryId, Instant now) {

        return jobs.existsByUserRepositoryIdAndTypeAndStateIn(userRepositoryId, JobType.ANALYZE, JobState.ACTIVE)
                || refreshes.existsByUserRepositoryIdAndCreatedAtAfter(userRepositoryId, now.minus(COOLDOWN));

    }
}
