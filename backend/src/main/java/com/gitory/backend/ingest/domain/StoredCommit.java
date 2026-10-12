package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.List;

public record StoredCommit(Long id, String sha, Instant authoredAt, Integer changedFiles, List<ChangedFile> files) {
}
