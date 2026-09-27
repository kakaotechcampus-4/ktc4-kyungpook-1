package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.JobErrorCode;
import com.gitory.backend.job.domain.JobResult;
import com.gitory.backend.job.domain.JobState;
import com.gitory.backend.job.domain.JobStep;
import com.gitory.backend.job.domain.JobType;
import com.gitory.backend.job.domain.JobView;

import java.time.Instant;
import java.util.List;

public record JobResponse(
        String jobId,
        JobType type,
        JobState state,
        boolean partial,
        List<JobStep> steps,
        JobErrorCode errorCode,
        Boolean retryable,
        Integer retryAfterSec,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        JobResult result,
        int pollAfterMs) {

    private static final int POLL_AFTER_MS = 2000;

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
