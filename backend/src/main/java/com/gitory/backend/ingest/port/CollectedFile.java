package com.gitory.backend.ingest.port;

import com.gitory.backend.ingest.domain.FileChangeStatus;

public record CollectedFile(String path, FileChangeStatus status, int additions, int deletions) {
}
