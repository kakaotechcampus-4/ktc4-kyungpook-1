package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class JobViewTest {

    private static final String KEY = "11111111-1111-4111-8111-111111111111";
    private static final UUID REPO_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final List<String> REASONS = List.of("내 커밋 52개 / 팀 145개", "PR 참여 없음");

    @ParameterizedTest
    @CsvSource({"3, false, OK", "1, true, PARTIAL", "0, false, EMPTY", "0, true, EMPTY"})
    @DisplayName("후보를 저장한 분석 Job 은 후보 보드와 같은 규칙으로 판정한다 — 후보가 없으면 부분 수집이어도 EMPTY, 후보가 있고 부분 수집이면 PARTIAL, 아니면 OK")
    void judgesByCandidateCountLikeTheBoard(int candidateCount, boolean partial, String verdict) {

        AnalysisJob job = groupedJob(candidateCount, partial);

        assertThat(JobView.from(job, REPO_ID, List.of(), () -> REASONS).result().verdict()).isEqualTo(verdict);

    }

    @ParameterizedTest
    @CsvSource({"3, false", "1, true"})
    @DisplayName("판정이 OK 나 PARTIAL 이면 이유 줄은 빈 목록이고 이유를 만들지도 않는다")
    void leavesReasonsEmptyUnlessEmpty(int candidateCount, boolean partial) {

        AtomicInteger calls = new AtomicInteger();
        AnalysisJob job = groupedJob(candidateCount, partial);

        JobResult result = JobView.from(job, REPO_ID, List.of(), () -> {
            calls.incrementAndGet();
            return REASONS;
        }).result();

        assertThat(result).isEqualTo(new JobResult(REPO_ID.toString(), null, result.verdict(), List.of()));
        assertThat(calls).hasValue(0);

    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    @DisplayName("판정이 EMPTY 이면 이유 줄에 후보 보드와 같은 줄을 담는다")
    void fillsReasonsWhenEmpty(boolean partial) {

        AnalysisJob job = groupedJob(0, partial);

        assertThat(JobView.from(job, REPO_ID, List.of(), () -> REASONS).result())
                .isEqualTo(new JobResult(REPO_ID.toString(), null, "EMPTY", REASONS));

    }

    @Test
    @DisplayName("경험 그룹화를 건너뛰었거나 후보 추리기가 끝나지 않은 Job 은 판정과 이유를 비우고 이유를 만들지도 않는다")
    void leavesVerdictEmptyUntilCandidatesAreStored() {

        AtomicInteger calls = new AtomicInteger();
        AnalysisJob skipped = collectedJob();
        skipped.succeed(false, null);
        AnalysisJob failedWhileGrouping = collectedJob();
        failedWhileGrouping.startGrouping();
        failedWhileGrouping.fail(JobErrorCode.INTERNAL_ERROR);

        JobResult noVerdict = new JobResult(REPO_ID.toString(), null, null, null);
        assertThat(JobView.from(skipped, REPO_ID, List.of(), () -> countedReasons(calls)).result()).isEqualTo(noVerdict);
        assertThat(JobView.from(failedWhileGrouping, REPO_ID, List.of(), () -> countedReasons(calls)).result())
                .isEqualTo(noVerdict);
        assertThat(calls).hasValue(0);

    }

    private static List<String> countedReasons(AtomicInteger calls) {

        calls.incrementAndGet();

        return REASONS;

    }

    private static AnalysisJob collectedJob() {

        AnalysisJob job = AnalysisJob.enqueue(1L, 10L, KEY);
        job.start();
        job.recordCollection(5L, 3, 1);
        return job;

    }

    private static AnalysisJob groupedJob(int candidateCount, boolean partial) {

        AnalysisJob job = collectedJob();
        job.startGrouping();
        job.recordCandidates(candidateCount);
        job.succeed(partial, null);
        return job;

    }

    @Test
    @DisplayName("카드 초안 Job 의 result 는 저장소 id 와 카드 id 를 담고 판정과 이유는 비운다")
    void draftJobResultCarriesCardIds() {

        AnalysisJob draft = AnalysisJob.enqueueDraft(1L, 10L, KEY);
        UUID first = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID second = UUID.fromString("44444444-4444-4444-8444-444444444444");

        assertThat(JobView.from(draft, REPO_ID, List.of(first, second), () -> REASONS).result())
                .isEqualTo(new JobResult(REPO_ID.toString(), List.of(first.toString(), second.toString()), null, null));

    }

    @Test
    @DisplayName("분석 Job 의 result 는 카드 id 를 담지 않는다")
    void analyzeJobResultIgnoresCardIds() {

        AnalysisJob skipped = collectedJob();
        skipped.succeed(false, null);

        assertThat(JobView.from(skipped, REPO_ID, List.of(UUID.randomUUID()), () -> REASONS).result().cardIds()).isNull();

    }
}
