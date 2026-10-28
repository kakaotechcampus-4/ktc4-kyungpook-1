package com.gitory.backend.job.domain;

import com.gitory.backend.common.infra.ai.AiProgress;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * AI 가 보낸 진행을 분석 Job 단계에 남기며, 같은 단계는 1초에 한 번까지만 쓰고 단계가 바뀌거나 다 읽은 순간은 바로 쓴다
 * 진행은 화면 표시용이라 쓰지 못해도 분석은 계속하고, 그사이 취소돼 RUNNING 이 아닌 Job 에는 쓰지 않는다
 */
@Slf4j
@RequiredArgsConstructor
class JobProgressRecorder implements Consumer<AiProgress> {

    private static final Duration INTERVAL = Duration.ofSeconds(1);

    private final Long jobId;
    private final AnalysisJobRepository jobs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    private JobStepKey lastStep;
    private Instant lastWrittenAt;

    @Override
    public void accept(AiProgress progress) {

        Optional<JobStepKey> step = stepOf(progress.step());
        Instant now = clock.instant();
        if (step.isEmpty() || throttled(step.get(), progress, now)) {
            return;
        }

        try {
            transaction.executeWithoutResult(status -> jobs.findWithLockById(jobId)
                    .filter(job -> job.getState() == JobState.RUNNING)
                    .ifPresent(job -> job.recordProgress(step.get(), progress.done(), progress.total())));
            lastStep = step.get();
            lastWrittenAt = now;
        } catch (RuntimeException failure) {
            log.warn("분석 Job {} 의 진행을 남기지 못했다", jobId, failure);
        }

    }

    private boolean throttled(JobStepKey step, AiProgress progress, Instant now) {

        boolean finished = progress.total() != null && progress.done() >= progress.total();
        return step == lastStep && !finished && now.isBefore(lastWrittenAt.plus(INTERVAL));

    }

    private static Optional<JobStepKey> stepOf(String step) {

        try {
            return Optional.of(JobStepKey.valueOf(step));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return Optional.empty();
        }

    }
}
