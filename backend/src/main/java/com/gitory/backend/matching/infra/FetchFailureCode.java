package com.gitory.backend.matching.infra;

public enum FetchFailureCode {
    HTTP_429,
    HTTP_STATUS,
    TIMEOUT,
    CONNECTION_FAILED,
    TOO_LARGE,
    ROBOTS_UNAVAILABLE,
    ROBOTS_DISALLOWED,
    INTERRUPTED
}
