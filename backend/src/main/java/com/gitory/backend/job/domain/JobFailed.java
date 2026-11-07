package com.gitory.backend.job.domain;

public record JobFailed(long jobId, JobType type, JobErrorCode errorCode) {

    static JobFailed of(AnalysisJob job) {

        return new JobFailed(job.getId(), job.getType(), job.getErrorCode());

    }
}
