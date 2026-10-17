package com.gitory.backend.ingest.domain;

public enum FileChangeStatus {
    ADDED,
    MODIFIED,
    REMOVED,
    RENAMED,
    COPIED,
    CHANGED,
    UNCHANGED,
    UNKNOWN
}
