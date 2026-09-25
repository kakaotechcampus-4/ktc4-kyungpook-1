package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.JobState;

public record StartedResponse(String jobId, JobState state, int pollAfterMs) {
}
