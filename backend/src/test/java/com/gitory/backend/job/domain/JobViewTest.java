package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

class JobViewTest {

    private static final String KEY = "11111111-1111-4111-8111-111111111111";
    private static final UUID REPO_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @ParameterizedTest
    @CsvSource({"3, false, OK", "1, true, PARTIAL", "0, false, EMPTY", "0, true, EMPTY"})
    @DisplayName("후보를 저장한 분석 Job 은 후보 보드와 같은 규칙으로 판정한다 — 후보가 없으면 부분 수집이어도 EMPTY, 후보가 있고 부분 수집이면 PARTIAL, 아니면 OK")
    void judgesByCandidateCountLikeTheBoard(int candidateCount, boolean partial, String verdict) {

        AnalysisJob job = groupedJob(candidateCount, partial);

        assertThat(JobView.from(job, REPO_ID).result())
                .isEqualTo(new JobResult(REPO_ID.toString(), null, verdict, List.of()));

    }

    @Test
    @DisplayName("묶기를 건너뛰었거나 후보 추리기가 끝나지 않은 Job 은 판정과 이유를 비운다")
    void leavesVerdictEmptyUntilCandidatesAreStored() {

        AnalysisJob skipped = collectedJob();
        skipped.succeed(false, null);
        AnalysisJob failedWhileGrouping = collectedJob();
        failedWhileGrouping.startGrouping();
        failedWhileGrouping.fail(JobErrorCode.INTERNAL_ERROR);

        JobResult noVerdict = new JobResult(REPO_ID.toString(), null, null, null);
        assertThat(JobView.from(skipped, REPO_ID).result()).isEqualTo(noVerdict);
        assertThat(JobView.from(failedWhileGrouping, REPO_ID).result()).isEqualTo(noVerdict);

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
}
