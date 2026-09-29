package com.gitory.backend.ingest.domain;

public enum ExclusionReason {
    BOT,
    MERGE_COMMIT,
    LOCKFILE_ONLY,
    NOT_OWN
}
