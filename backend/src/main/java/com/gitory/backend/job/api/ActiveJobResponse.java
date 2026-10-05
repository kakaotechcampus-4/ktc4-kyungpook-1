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

    static ActiveJobResponse from(ActiveJobRow row) {
        return new ActiveJobResponse(
                row.jobId().toString(),
                JobState.valueOf(row.state()),
                JobType.valueOf(row.type()),
                row.userRepositoryId().toString(),
                row.repoName(),
                row.startedAt(),
                JobResponse.POLL_AFTER_MS);
    }
}
