package com.gitory.backend.job.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    static JobView from(AnalysisJob job, UUID repoId) {

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
                result(job.getType(), repoId),
                job.isTerminal());

    }

    private static Boolean retryable(JobErrorCode errorCode) {

        if (errorCode == null) {
            return null;
        }

        return errorCode == JobErrorCode.GITHUB_UNAVAILABLE || errorCode == JobErrorCode.GITHUB_RATE_LIMITED;
    }

    private static JobResult result(JobType type, UUID repoId) {

        if (type != JobType.ANALYZE) {
            return null;
        }

        return new JobResult(repoId.toString(), null, null, null);

    }
}
