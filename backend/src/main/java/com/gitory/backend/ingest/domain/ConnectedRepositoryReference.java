package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.UUID;

public record ConnectedRepositoryReference(Long id, UUID publicId, String ownerLogin, String name,
                                           Instant lastAnalyzedAt) {
}
