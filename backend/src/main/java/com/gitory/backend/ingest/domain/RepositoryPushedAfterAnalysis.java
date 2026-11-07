package com.gitory.backend.ingest.domain;

import java.time.Instant;

public record RepositoryPushedAfterAnalysis(Long id, Instant lastAnalyzedAt) {
}
