package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.List;

public record MeRepositoryStats(List<Long> userRepositoryIds, int analyzedRepos, Instant lastAnalyzedAt) {
}
