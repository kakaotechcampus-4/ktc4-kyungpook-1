package com.gitory.backend.ingest.domain;

import java.time.Instant;

public record MeRepositoryTotals(long analyzedRepos, Instant lastAnalyzedAt) {
}
