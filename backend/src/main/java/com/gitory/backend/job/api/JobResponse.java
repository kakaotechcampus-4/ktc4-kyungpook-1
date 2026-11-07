package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.CardDraftLive;
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
        @Schema(description = "카드 초안 Job 이 쓰는 카드를 쓰는 순서대로. 카드마다 state 는 WAITING → READING(코드 읽는 중) → "
                + "WRITING(칸 쓰는 중) → DONE·FAILED, slots 는 칸마다 지금까지 쓴 전체 글 하나로 다시 쓰면 round 가 올라가며 "
                + "done 인데 text 가 빈 글이면 AI 가 근거가 없어 비운 칸이다. steps 는 카드 상세 draftSteps 와 같은 모양이다. "
                + "카드 초안 Job 이 아니거나, Job 이 끝나고 10분 뒤나 서버가 다시 시작되면 빈 목록이다") List<CardDraftLive> drafts,
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
                job.drafts(),
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
