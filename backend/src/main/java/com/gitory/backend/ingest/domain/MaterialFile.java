package com.gitory.backend.ingest.domain;

public record MaterialFile(String path, FileChangeStatus status, int additions, int deletions) {
}
