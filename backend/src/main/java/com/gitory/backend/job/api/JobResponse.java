package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.JobActivity;
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
        @Schema(description = "AI 가 방금 읽거나 판단한 줄, 오래된 것부터. 읽기 줄(PULL_REQUEST·COMMIT)은 단계마다 최근 20줄, "
                + "판단 줄(GROUPED·DESCRIBED)은 모두 싣고, Job 이 끝나고 10분 뒤나 서버가 다시 시작되면 빈 목록이다. "
                + "seq 는 Job 안에서 1부터 늘어나 이미 받은 줄을 가를 수 있다") List<JobActivity> activity,
        JobErrorCode errorCode,
        Boolean retryable,
        Integer retryAfterSec,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        JobResult result,
        @Schema(description = "다음 조회 간격(밀리초). 종료 상태는 0", example = "1000") int pollAfterMs) {

    // 진행 숫자는 1초에 한 번까지만 저장해, 더 자주 읽어도 같은 값을 받는다
    static final int POLL_AFTER_MS = 1000;

    static JobResponse from(JobView job) {
        return new JobResponse(
                job.jobId(),
                job.type(),
                job.state(),
                job.partial(),
                job.steps(),
                job.activity(),
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
