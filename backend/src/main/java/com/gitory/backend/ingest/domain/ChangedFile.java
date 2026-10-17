package com.gitory.backend.ingest.domain;

public record ChangedFile(String path, int additions, int deletions) {

    public int lines() {

        return additions + deletions;

    }
}
