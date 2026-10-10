package com.gitory.backend.ingest.domain;

import java.util.UUID;

public record RepositoryReference(UUID publicId, String ownerLogin, String name) {
}
