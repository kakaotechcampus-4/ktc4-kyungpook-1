package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

class AnalysisJobTest {

    private static final String KEY = "11111111-1111-4111-8111-111111111111";

    private static AnalysisJob queued() {
        return AnalysisJob.enqueue(1L,10L,KEY);
    }

    private static AnalysisJob running() {
        AnalysisJob job = queued();
        job.start();
        return job;
    }

    @Test
    @DisplayName("접수된 Job 의 초기 상태는 QUEUED 이고 부분 완료가 아니다.")
    void enqueuedJobStartsQueued() {
        AnalysisJob job = queued();

        assertThat(job.getState()).isEqualTo(JobState.QUEUED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("start 메서드는 QUEUED 를 RUNNING 상태로 바꾼다.")
    void startMovesQueuedToRunning() {

        AnalysisJob job = queued();

        job.start();

        assertThat(job.getState()).isEqualTo(JobState.RUNNING);
    }


    @Test
    @DisplayName("RUNNING(아직 실행이 되지 않은) 이 아닌 Job 은 succeed · fail 할 수 없다")
    void finishFromNonRunningIsRejected() {
        assertThatThrownBy(() -> queued().succeed(false, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> queued().fail(JobErrorCode.INTERNAL_ERROR)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("succeed 는 SUCCEEDED 로 바꾸고 partial 과 끝난 시각을 남긴다")
    void succeedRecordPartialAndFinishedAt() {

        AnalysisJob job = running();

        job.succeed(true, null);

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.getFinishedAt()).isNotNull();


    }

    @Test
    @DisplayName("fail 상태를 FAILED 로 변경 후  errorCode 와 끝난 시각을 남긴다")
    void failRecordsErrorCodeAndFinishedAt() {

        AnalysisJob job = running();

        job.fail(JobErrorCode.GITHUB_RATE_LIMITED);

        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("errorCode 없이 fail 하면 거절되고 상태는 그대로 유지된다")
    void failWithoutErrorCodeIsRejected() {

        AnalysisJob job = running();

        assertThatThrownBy(() -> job.fail(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getState()).isEqualTo(JobState.RUNNING);
    }

    @Test
    @DisplayName("끝난 Job 은 상태를 다시 바꿀 수 없다.")
    void terminalJobCannotTransition() {

        AnalysisJob job = running();
        job.fail(JobErrorCode.INTERNAL_ERROR);

        assertThatThrownBy(() -> job.succeed(false, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(job::start).isInstanceOf(IllegalStateException.class);
        assertThat(job.getState()).isEqualTo(JobState.FAILED);
    }

    @Test
    @DisplayName("start 는 Job 상태와 COMMITS 단계를 RUNNING 으로 바꾼다")
    void startMarksCommitStepRunning() {

        AnalysisJob job = running();

        assertThat(job.getSteps()).extracting(JobStep::state)
                .containsExactly(JobStepState.RUNNING, JobStepState.QUEUED, JobStepState.QUEUED, JobStepState.QUEUED);

    }

    @Test
    @DisplayName("수집 기록을 남기면 커밋·PR 읽기 단계는 읽은 개수로 완료되고, 후보 추리기·추천 이유 단계는 SKIPPED 상태로 나타낸다")
    void recordCollectionFinishesCollectionSteps() {

        AnalysisJob job = running();

        job.recordCollection(5L, 12, 3);

        assertThat(job.getCollectionRunId()).isEqualTo(5L);
        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 12, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 3, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    @Test
    @DisplayName("묶기를 시작하면 후보 추리기는 진행 중, 추천 이유는 대기로 바뀌고 수집 두 단계는 그대로다")
    void startGroupingMarksCompressRunning() {

        AnalysisJob job = running();
        job.recordCollection(5L, 12, 3);

        job.startGrouping();

        assertThat(job.getState()).isEqualTo(JobState.RUNNING);
        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 12, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 3, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.RUNNING, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.QUEUED, 0, null));

    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    @DisplayName("후보 수를 남기면 후보 추리기·추천 이유는 그 수로 완료되고 수집 두 단계는 그대로다")
    void recordCandidatesFinishesCandidateSteps(int candidateCount) {

        AnalysisJob job = running();
        job.recordCollection(5L, 12, 3);
        job.startGrouping();

        job.recordCandidates(candidateCount);

        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 12, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 3, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.DONE, candidateCount, null),
                new JobStep(JobStepKey.REASON, JobStepState.DONE, candidateCount, null));

    }

    @Test
    @DisplayName("후보가 하나도 없으면 후보 추리기는 0 으로 완료되고 추천 이유만 SKIPPED 로 둔다")
    void noCandidateSkipsReasonStep() {

        AnalysisJob job = running();
        job.recordCollection(5L, 12, 3);
        job.startGrouping();

        job.recordCandidates(0);

        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 12, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 3, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.DONE, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    @Test
    @DisplayName("RUNNING 이 아닌 Job 은 묶기를 시작하거나 후보 수를 남길 수 없다")
    void groupingStepsRequireRunning() {

        AnalysisJob finished = running();
        finished.succeed(false, null);
        List<JobStep> finishedSteps = finished.getSteps();

        assertThatThrownBy(() -> queued().startGrouping()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> queued().recordCandidates(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(finished::startGrouping).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> finished.recordCandidates(1)).isInstanceOf(IllegalStateException.class);
        assertThat(finished.getSteps()).isEqualTo(finishedSteps);

    }

    @Test
    @DisplayName("부분 완료에는 일부만 읽은 이유를 에러 코드로 남길 수 있다")
    void partialSuccessKeepsReason() {

        AnalysisJob job = running();

        job.succeed(true, JobErrorCode.GITHUB_RATE_LIMITED);

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);

    }

    @Test
    @DisplayName("부분 완료가 아닌데 에러 코드를 남기면 거절되고 상태는 그대로 유지된다")
    void fullSuccessWithErrorCodeIsRejected() {

        AnalysisJob job = running();

        assertThatThrownBy(() -> job.succeed(false, JobErrorCode.GITHUB_RATE_LIMITED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getState()).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("GitHub 한도 부분 완료에 풀리는 시각을 주면 끝난 시각부터 남은 초를 올림해 남긴다")
    void retryAfterSecIsRoundedUpFromFinishedAt() {

        AnalysisJob job = running();

        job.succeed(true, JobErrorCode.GITHUB_RATE_LIMITED, Instant.now().plusMillis(600_500));

        assertThat(job.getRetryAfterSec()).isEqualTo(601);

    }

    @Test
    @DisplayName("풀리는 시각이 끝난 시각보다 이르면 남은 초를 비워 둔다")
    void retryAfterSecIsEmptyWhenLimitAlreadyReset() {

        AnalysisJob job = running();

        job.succeed(true, JobErrorCode.GITHUB_RATE_LIMITED, Instant.now().minusSeconds(1));

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.getRetryAfterSec()).isNull();

    }

    @Test
    @DisplayName("풀리는 시각을 모르면 GitHub 한도 부분 완료여도 남은 초는 비어 있다")
    void retryAfterSecIsEmptyWhenResetUnknown() {

        AnalysisJob job = running();

        job.succeed(true, JobErrorCode.GITHUB_RATE_LIMITED, null);

        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);
        assertThat(job.getRetryAfterSec()).isNull();

    }

    @Test
    @DisplayName("GitHub 한도 부분 완료가 아닌데 풀리는 시각을 주면 거절되고 상태는 그대로 유지된다")
    void resetTimeWithoutRateLimitIsRejected() {

        AnalysisJob job = running();
        Instant resetAt = Instant.now().plusSeconds(60);

        assertThatThrownBy(() -> job.succeed(true, null, resetAt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.succeed(true, JobErrorCode.GITHUB_UNAVAILABLE, resetAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.succeed(false, null, resetAt)).isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getState()).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("풀리는 시각을 받지 않는 succeed·fail·cancel 로 끝난 Job 의 남은 초는 비어 있다")
    void otherEndingsLeaveRetryAfterSecEmpty() {

        AnalysisJob succeeded = running();
        AnalysisJob failed = running();
        AnalysisJob canceled = running();

        succeeded.succeed(true, JobErrorCode.GITHUB_RATE_LIMITED);
        failed.fail(JobErrorCode.GITHUB_UNAVAILABLE);
        canceled.cancel();

        assertThat(succeeded.getRetryAfterSec()).isNull();
        assertThat(failed.getRetryAfterSec()).isNull();
        assertThat(canceled.getRetryAfterSec()).isNull();

    }

    @Test
    @DisplayName("cancel 은 QUEUED·RUNNING Job 을 CANCELED 로 바꾸고 끝난 시각을 남긴다")
    void cancelEndsActiveJob() {

        AnalysisJob queued = queued();
        AnalysisJob running = running();

        queued.cancel();
        running.cancel();

        assertThat(queued.getState()).isEqualTo(JobState.CANCELED);
        assertThat(running.getState()).isEqualTo(JobState.CANCELED);
        assertThat(running.getFinishedAt()).isNotNull();

    }

    @Test
    @DisplayName("cancel 은 이미 끝난 Job 을 바꾸지 않는다")
    void cancelKeepsFinishedJob() {

        AnalysisJob job = running();
        job.succeed(false, null);
        Instant finishedAt = job.getFinishedAt();

        job.cancel();

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.getFinishedAt()).isEqualTo(finishedAt);

    }

    @Test
    @DisplayName("requeue 는 RUNNING Job 을 QUEUED 로 되돌리고 진행 단계도 접수했을 때처럼 모두 QUEUED 로 돌린다")
    void requeueReturnsRunningJobToQueue() {

        AnalysisJob job = running();

        job.requeue();

        assertThat(job.getState()).isEqualTo(JobState.QUEUED);
        assertThat(job.getSteps()).isEqualTo(queued().getSteps());

    }

    @Test
    @DisplayName("RUNNING 이 아닌 Job 은 requeue 할 수 없다")
    void requeueFromNonRunningIsRejected() {

        AnalysisJob finished = running();
        finished.succeed(false, null);

        assertThatThrownBy(() -> queued().requeue()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(finished::requeue).isInstanceOf(IllegalStateException.class);
        assertThat(finished.getState()).isEqualTo(JobState.SUCCEEDED);

    }

    @Test
    @DisplayName("카드 초안 Job 은 DRAFT 종류로 QUEUED 에서 시작하고 네 단계가 모두 QUEUED 다")
    void draftJobStartsQueuedAsDraftType() {

        AnalysisJob job = AnalysisJob.enqueueDraft(1L, 10L, KEY);

        assertThat(job.getType()).isEqualTo(JobType.DRAFT);
        assertThat(job.getState()).isEqualTo(JobState.QUEUED);
        assertThat(job.getSteps()).isEqualTo(queued().getSteps());
        assertThat(queued().getType()).isEqualTo(JobType.ANALYZE);

    }

    @Test
    @DisplayName("카드 초안 Job 은 AI 없이 네 단계를 모두 SKIPPED 로 두고 부분 완료와 에러 코드 없이 SUCCEEDED 로 끝난다")
    void draftJobCompletesWithoutWriting() {

        AnalysisJob job = AnalysisJob.enqueueDraft(1L, 10L, KEY);
        job.start();

        job.completeDraftWithoutWriting();

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.getErrorCode()).isNull();
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    @Test
    @DisplayName("RUNNING 이 아닌 카드 초안 Job 이나 분석 Job 은 AI 없이 끝낼 수 없다")
    void completingWithoutWritingNeedsRunningDraftJob() {

        AnalysisJob queuedDraft = AnalysisJob.enqueueDraft(1L, 10L, KEY);

        assertThatThrownBy(queuedDraft::completeDraftWithoutWriting).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> running().completeDraftWithoutWriting()).isInstanceOf(IllegalStateException.class);
        assertThat(queuedDraft.getState()).isEqualTo(JobState.QUEUED);

    }
}
