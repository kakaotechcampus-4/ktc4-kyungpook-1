package com.gitory.backend.job.domain;

import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.job.infra.RepoRefreshRepository;
import com.gitory.backend.recommend.domain.RefreshCandidatePicker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 스스로 다시 읽은 분석이 끝나면 새 일이 든 후보로 카드 초안을 시작하고, 일이 끝난 Job 에 맞춰 기록을 닫거나 알릴 것으로 바꾼다
 * 어떤 예외도 분석 Job 의 결과나 워커로 새지 않게 여기서 삼킨다
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(CardCreationProperties.class)
public class RepoRefreshFollowUp {

    private final AnalysisJobRepository jobs;
    private final RepoRefreshRepository refreshes;
    private final RefreshCandidatePicker picker;
    private final RepoRefreshRecorder recorder;
    private final CardCreationProperties limits;

    // 분석 Job 의 저장이 모두 끝난 뒤 트랜잭션 밖에서 불려, 카드를 만드는 일이 Job 행을 붙잡지 않는다
    @EventListener
    public void analysisFinished(AnalysisFinished event) {

        try {
            draftNewWork(event.jobId());
        } catch (RuntimeException failure) {
            log.warn("분석 Job {} 이 끝난 뒤 카드 초안을 시작하지 못해 알리지 않고 닫는다", event.jobId(), failure);
            close(event.jobId());
        }

    }

    // 실패가 롤백되면 닫지 않도록 커밋이 끝난 뒤에 받는다
    @TransactionalEventListener
    public void jobFailed(JobFailed event) {

        settle(event.jobId());

    }

    @TransactionalEventListener
    public void jobEnded(JobEnded event) {

        settle(event.jobId());

    }

    private void draftNewWork(Long analysisJobId) {

        Optional<RepoRefresh> found = refreshes.findByAnalysisJobId(analysisJobId)
                .filter(refresh -> refresh.getState() == RepoRefreshState.READING);
        if (found.isEmpty()) {
            return;
        }

        AnalysisJob job = jobs.findById(analysisJobId).orElseThrow();
        if (job.getState() != JobState.SUCCEEDED || job.isPartial()) {
            recorder.closeReading(analysisJobId);
            return;
        }

        List<UUID> picked = picker.pick(job.getUserRepositoryId(), found.get().getPreviousAnalyzedAt(),
                job.getCollectionRunId(), limits.maxPerRequest());
        if (picked.isEmpty()) {
            recorder.closeReading(analysisJobId);
            return;
        }

        recorder.startDrafting(analysisJobId, job.getUserId(), job.getUserRepositoryId(), picked);

    }

    private void close(Long analysisJobId) {

        try {
            recorder.closeReading(analysisJobId);
        } catch (RuntimeException failure) {
            log.warn("분석 Job {} 의 스스로 다시 읽기 기록을 닫지 못했다", analysisJobId, failure);
        }

    }

    private void settle(Long jobId) {

        try {
            recorder.settle(jobId);
        } catch (RuntimeException failure) {
            log.warn("Job {} 이 끝난 뒤 스스로 다시 읽기 기록을 바꾸지 못했다", jobId, failure);
        }

    }
}
