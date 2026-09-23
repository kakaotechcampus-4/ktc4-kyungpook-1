package com.gitory.backend.job.domain;

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
        JobResultResponse result,
        int pollAfterMs) {

    private static final int POLL_AFTER_MS = 2000;

    static JobResponse from(AnalysisJob job) {
        return new JobResponse(
                job.getPublicId().toString(),
                job.getType(),
                job.getState(),
                job.isPartial(),
                job.getSteps(),
                job.getErrorCode(),
                retryable(job.getErrorCode()),
                null,
                job.getStartedAt(),
                job.getUpdatedAt(),
                job.getFinishedAt(),
                null,
                job.isTerminal() ? 0 : POLL_AFTER_MS);
    }

    private static Boolean retryable(JobErrorCode errorCode) {

        if (errorCode == null) {
            return null;
        }

        return errorCode == JobErrorCode.GITHUB_UNAVAILABLE || errorCode == JobErrorCode.GITHUB_RATE_LIMITED;
    }
}
