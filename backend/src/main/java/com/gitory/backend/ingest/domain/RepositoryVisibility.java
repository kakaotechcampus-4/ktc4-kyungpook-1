package com.gitory.backend.ingest.domain;

public enum RepositoryVisibility {
    PUBLIC,
    PRIVATE;

    public static RepositoryVisibility of(boolean privateRepository) {
        return privateRepository ? PRIVATE : PUBLIC;
    }
}
