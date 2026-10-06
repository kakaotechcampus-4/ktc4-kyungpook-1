package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.JobErrorCode;
import com.gitory.backend.job.domain.JobResult;
import com.gitory.backend.job.domain.JobState;
import com.gitory.backend.job.domain.JobStep;
import com.gitory.backend.job.domain.JobType;
import com.gitory.backend.job.domain.JobView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Job 상세. 부분 결과는 별도 상태가 아니라 partial로 표시합니다.")
public record JobResponse(
        @Schema(format = "uuid") String jobId,
        JobType type,
        JobState state,
        @Schema(description = "상한·요청 제한 등에 따른 부분 결과 여부") boolean partial,
        List<JobStep> steps,
        JobErrorCode errorCode,
        Boolean retryable,
        Integer retryAfterSec,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        JobResult result,
        @Schema(description = "다음 조회 간격(밀리초). 종료 상태는 0", example = "2000") int pollAfterMs) {

    static final int POLL_AFTER_MS = 2000;

    static JobResponse from(JobView job) {
        return new JobResponse(
                job.jobId(),
                job.type(),
                job.state(),
                job.partial(),
                job.steps(),
                job.errorCode(),
                job.retryable(),
                job.retryAfterSec(),
                job.startedAt(),
                job.updatedAt(),
                job.finishedAt(),
                job.result(),
                job.terminal() ? 0 : POLL_AFTER_MS);
    }
}
