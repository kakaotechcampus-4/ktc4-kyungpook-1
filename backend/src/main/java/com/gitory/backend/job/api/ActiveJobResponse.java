package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.ActiveJobRow;
import com.gitory.backend.job.domain.JobState;
import com.gitory.backend.job.domain.JobType;

import java.time.Instant;

public record ActiveJobResponse(
        String jobId,
        JobState state,
        JobType type,
        String userRepositoryId,
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
