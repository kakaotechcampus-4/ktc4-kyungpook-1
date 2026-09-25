package com.gitory.backend.job.domain;

public record JobStep(JobStepKey key, JobStepState state, int done, Integer total) {
}
