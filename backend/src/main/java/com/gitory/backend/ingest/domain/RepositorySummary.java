package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.UUID;

public record RepositorySummary(
        UUID publicId,
        String ownerLogin,
        String name,
        String language,
        Instant activeFrom,
        Instant activeTo,
        Instant lastAnalyzedAt) {
}
