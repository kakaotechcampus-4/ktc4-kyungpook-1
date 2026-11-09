package com.gitory.backend.job.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class JobFailureAlertTest {

    private final JobAlertSender sender = mock(JobAlertSender.class);
    private final JobFailureAlert alert = new JobFailureAlert(sender);

    @ParameterizedTest
    @EnumSource(value = JobErrorCode.class, names = {"INTERNAL_ERROR", "AI_UNAVAILABLE", "DRAFT_TIMEOUT"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("사용자나 GitHub 쪽 이유로 실패한 Job 은 알리지 않는다")
    void doesNotAlertForUserOrGithubReasons(JobErrorCode errorCode) {

        alert.jobFailed(new JobFailed(7L, JobType.ANALYZE, errorCode));

        verifyNoInteractions(sender);

    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ANALYZE | INTERNAL_ERROR | [Gitory] 분석 Job 123 이 INTERNAL_ERROR 로 실패했습니다",
            "DRAFT | AI_UNAVAILABLE | [Gitory] 카드 초안 Job 123 이 AI_UNAVAILABLE 로 실패했습니다",
            "DRAFT | DRAFT_TIMEOUT | [Gitory] 카드 초안 Job 123 이 DRAFT_TIMEOUT 로 실패했습니다"})
    @DisplayName("알림 글에는 Job 종류와 실패 코드, Job id 만 담는다")
    void sendsOnlyKindCodeAndJobId(JobType type, JobErrorCode errorCode, String expected) {

        alert.jobFailed(new JobFailed(123L, type, errorCode));

        verify(sender).send(expected);

    }
}
