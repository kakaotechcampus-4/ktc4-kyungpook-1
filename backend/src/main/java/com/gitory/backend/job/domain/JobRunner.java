package com.gitory.backend.job.domain;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.ingest.domain.ActivityStoreService;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.IngestRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 대기 중인 분석 Job 하나를 꺼내 AI 수집을 부르고 결과에 따라 끝낸다
 * Job 을 RUNNING 으로 확정한 뒤에 AI 를 불러, 응답을 기다리는 동안 DB 커넥션과 잠금을 붙잡지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobRunner {

    // AI 응답 대기 한도(180초)보다 넉넉하게 잡아, 실행 중에 서버가 꺼져 남은 Job 만 걸리게 한다
    static final Duration STUCK_AFTER = Duration.ofMinutes(10);

    private static final Set<String> GITHUB_FAILURES =
            Set.of("GITHUB_API_ERROR", "RESOURCE_NOT_FOUND", "GITHUB_CONNECTION_UNAVAILABLE");

    private final AnalysisJobRepository jobs;
    private final RepositoryActivityPort activities;
    private final ActivityStoreService activityStore;
    private final TransactionTemplate transaction;

    /** 꺼낼 Job 이 없으면 false 를 돌려준다 */
    public boolean runNext() {

        Optional<AnalysisJob> claimed = transaction.execute(status -> claimNext());
        if (claimed.isEmpty()) {
            return false;
        }

        run(claimed.get());
        return true;

    }

    public void failStuckJobs() {

        Instant before = Instant.now().minus(STUCK_AFTER);
        transaction.executeWithoutResult(status -> jobs.findByStateAndUpdatedAtBefore(JobState.RUNNING, before)
                .forEach(job -> job.fail(JobErrorCode.INTERNAL_ERROR)));

    }

    private Optional<AnalysisJob> claimNext() {

        Optional<AnalysisJob> next = jobs.findNextQueuedForUpdate();
        next.ifPresent(AnalysisJob::start);
        return next;

    }

    private void run(AnalysisJob job) {

        IngestRequest request = new IngestRequest(job.getUserRepositoryId(), List.of(), null);

        try {
            CollectedActivity activity = activities.collect(request);
            transaction.executeWithoutResult(status -> succeed(job.getId(), request, activity));
        } catch (RuntimeException failure) {
            JobErrorCode errorCode = errorCodeOf(failure);
            log.warn("분석 Job {} 을 {} 로 끝낸다", job.getId(), errorCode, failure);
            transaction.executeWithoutResult(status -> jobs.findById(job.getId()).orElseThrow().fail(errorCode));
        }

    }

    private void succeed(Long jobId, IngestRequest request, CollectedActivity activity) {

        Long collectionRunId = activityStore.store(request, activity);

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        job.recordCollection(collectionRunId, activity.commits().size(), activity.pullRequests().size());
        job.succeed(activity.partialReason() != null, partialErrorCodeOf(activity.partialReason()));

    }

    /** 우리 상한은 다시 읽어도 같은 결과라 이어 읽기가 열리지 않도록 에러 코드를 남기지 않는다 */
    private static JobErrorCode partialErrorCodeOf(PartialReason reason) {

        return reason == PartialReason.GITHUB_RATE_LIMITED ? JobErrorCode.GITHUB_RATE_LIMITED : null;

    }

    private static JobErrorCode errorCodeOf(RuntimeException failure) {

        if (failure instanceof AiClientException ai && GITHUB_FAILURES.contains(ai.errorCode())) {
            return JobErrorCode.GITHUB_UNAVAILABLE;
        }

        return JobErrorCode.INTERNAL_ERROR;

    }
}
