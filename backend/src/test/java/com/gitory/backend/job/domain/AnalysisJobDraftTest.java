package com.gitory.backend.job.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

class AnalysisJobDraftTest {

    private static final String KEY = "11111111-1111-4111-8111-111111111111";

    @Test
    @DisplayName("진행 중에는 커밋 읽기를 diff 근거까지 마친 카드 수로, 후보로 추리기·추천 이유 붙이기를 저장까지 마친 카드 수로 남기고 PR 읽기는 건너뛴다")
    void recordsDraftProgressPerCard() {

        AnalysisJob job = runningDraft();

        job.recordDraftProgress(2, 1, 3);

        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.RUNNING, 2, 3),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.RUNNING, 1, 3),
                new JobStep(JobStepKey.REASON, JobStepState.RUNNING, 1, 3));

    }

    @Test
    @DisplayName("아직 아무 카드도 시작하지 않았으면 커밋 읽기만 진행 중이고 나머지는 대기다")
    void startsWithOnlyCommitStepRunning() {

        AnalysisJob job = runningDraft();

        job.recordDraftProgress(0, 0, 2);

        assertThat(job.getSteps()).extracting(JobStep::state).containsExactly(JobStepState.RUNNING,
                JobStepState.SKIPPED, JobStepState.QUEUED, JobStepState.QUEUED);

    }

    @Test
    @DisplayName("모든 카드를 채우거나 건너뛰었으면 부분 완료 없이 끝나고 진행 단계는 모두 완료다")
    void finishesWithoutPartialWhenEveryCardIsDone() {

        AnalysisJob job = runningDraft();

        job.finishDraft(List.of(CardDraftResult.DRAFTED, CardDraftResult.SKIPPED));

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.getErrorCode()).isNull();
        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 2, 2),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.DONE, 2, 2),
                new JobStep(JobStepKey.REASON, JobStepState.DONE, 2, 2));

    }

    @ParameterizedTest
    @MethodSource("partialResults")
    @DisplayName("덜 된 카드가 있으면 부분 완료로 끝나고, 이유가 여럿이면 다시 연결, 내부 오류, AI 일시 장애, 시간 초과, 근거 없음 순서로 하나만 남긴다")
    void finishesPartialWithOneReason(List<CardDraftResult> results, JobErrorCode expected) {

        AnalysisJob job = runningDraft();

        job.finishDraft(results);

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(expected);

    }

    @Test
    @DisplayName("모든 카드가 실패하면 Job 은 AI 오류로 실패한다")
    void failsWhenEveryCardFailed() {

        AnalysisJob job = runningDraft();

        job.finishDraft(List.of(CardDraftResult.FAILED, CardDraftResult.FAILED));

        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("모든 카드가 실패하고 그중 토큰 거절이 있으면 Job 은 다시 연결해야 하는 GITHUB_UNAUTHORIZED 로 실패한다")
    void failsWithTokenRejectionFirst() {

        AnalysisJob job = runningDraft();

        job.finishDraft(List.of(CardDraftResult.FAILED, CardDraftResult.GITHUB_UNAUTHORIZED));

        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_UNAUTHORIZED);

    }

    @Test
    @DisplayName("채울 카드가 하나도 없으면 실패가 아니라 부분 완료 없이 끝난다")
    void finishesWithoutCards() {

        AnalysisJob job = runningDraft();

        job.finishDraft(List.of());

        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();

    }

    @Test
    @DisplayName("분석 Job 이나 실행 중이 아닌 초안 Job 에는 초안 진행을 남기거나 초안으로 끝낼 수 없다")
    void rejectsDraftStepsOnOtherJobs() {

        AnalysisJob analyze = AnalysisJob.enqueue(1L, 10L, KEY);
        analyze.start();
        AnalysisJob queuedDraft = AnalysisJob.enqueueDraft(1L, 10L, KEY);

        assertThatThrownBy(() -> analyze.recordDraftProgress(0, 0, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analyze.finishDraft(List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> queuedDraft.recordDraftProgress(0, 0, 1)).isInstanceOf(IllegalStateException.class);

    }

    static Stream<Arguments> partialResults() {

        return Stream.of(
                argumentSet("시간 초과 카드가 있다", List.of(CardDraftResult.DRAFTED, CardDraftResult.TIMED_OUT),
                        JobErrorCode.DRAFT_TIMEOUT),
                argumentSet("근거 없는 카드가 있다", List.of(CardDraftResult.NO_EVIDENCE, CardDraftResult.SKIPPED),
                        JobErrorCode.EVIDENCE_MISSING),
                argumentSet("실패한 카드가 있다", List.of(CardDraftResult.FAILED, CardDraftResult.DRAFTED),
                        JobErrorCode.INTERNAL_ERROR),
                argumentSet("시간 초과와 근거 없음이 섞였다", List.of(CardDraftResult.NO_EVIDENCE, CardDraftResult.TIMED_OUT),
                        JobErrorCode.DRAFT_TIMEOUT),
                argumentSet("실패와 시간 초과와 근거 없음이 섞였다", List.of(CardDraftResult.NO_EVIDENCE,
                        CardDraftResult.TIMED_OUT, CardDraftResult.FAILED), JobErrorCode.INTERNAL_ERROR),
                argumentSet("토큰 거절과 내부 오류가 섞였다", List.of(CardDraftResult.FAILED,
                        CardDraftResult.GITHUB_UNAUTHORIZED, CardDraftResult.DRAFTED), JobErrorCode.GITHUB_UNAUTHORIZED));

    }

    private static AnalysisJob runningDraft() {

        AnalysisJob job = AnalysisJob.enqueueDraft(1L, 10L, KEY);
        job.start();
        return job;

    }
}
