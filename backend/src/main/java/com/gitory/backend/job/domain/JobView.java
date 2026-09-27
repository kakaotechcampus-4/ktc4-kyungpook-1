package com.gitory.backend.job.domain;

import java.time.Instant;
import java.util.List;

public record JobView(
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
        boolean terminal) {

    static JobView from(AnalysisJob job) {
        return new JobView(
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
                job.isTerminal());
    }

    private static Boolean retryable(JobErrorCode errorCode) {

        if (errorCode == null) {
            return null;
        }

        return errorCode == JobErrorCode.GITHUB_UNAVAILABLE || errorCode == JobErrorCode.GITHUB_RATE_LIMITED;
    }
}
