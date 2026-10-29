package com.gitory.backend.ingest.domain;

import java.time.Instant;

public record CommitSummary(Long id, String sha, String message, Instant authoredAt, Integer changedFiles,
                            String diffSummary) {
}
