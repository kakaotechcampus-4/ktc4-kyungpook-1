package com.gitory.backend.job.domain;

import java.util.List;

public enum JobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public static final List<JobState> ACTIVE = List.of(QUEUED, RUNNING);

    public static final List<String> ACTIVE_NAMES = ACTIVE.stream().map(Enum::name).toList();
}
