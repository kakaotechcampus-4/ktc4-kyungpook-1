package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}