package com.gitory.backend.job.domain;

public enum CardDraftResult {
    DRAFTED,
    SKIPPED,
    NO_EVIDENCE,
    TIMED_OUT,
    GITHUB_UNAUTHORIZED,
    AI_UNAVAILABLE,
    FAILED
}
