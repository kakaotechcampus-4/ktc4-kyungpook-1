package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.ActiveJobRow;
import com.gitory.backend.job.domain.JobState;
import com.gitory.backend.job.domain.JobType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "QUEUED 또는 RUNNING인 본인의 Job 요약")
public record ActiveJobResponse(
        @Schema(format = "uuid") String jobId,
        JobState state,
        JobType type,
        @Schema(description = "선택한 저장소의 공개 UUID", format = "uuid") String userRepositoryId,
        String repoName,
        Instant startedAt,
        int pollAfterMs) {

    // 목록에는 실시간 줄이 없어 Job 상세(1초)보다 드물게 읽어도 된다
    private static final int POLL_AFTER_MS = 2000;

    static ActiveJobResponse from(ActiveJobRow row) {
        return new ActiveJobResponse(
                row.jobId().toString(),
                JobState.valueOf(row.state()),
                JobType.valueOf(row.type()),
                row.userRepositoryId().toString(),
                row.repoName(),
                row.startedAt(),
                POLL_AFTER_MS);
    }
}
