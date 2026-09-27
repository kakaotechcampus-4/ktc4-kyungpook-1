package com.gitory.backend.job.domain;

public enum JobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
