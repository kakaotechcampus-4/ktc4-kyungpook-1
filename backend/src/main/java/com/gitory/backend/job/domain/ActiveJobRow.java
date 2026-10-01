package com.gitory.backend.job.domain;

import java.time.Instant;
import java.util.UUID;

public record ActiveJobRow(UUID jobId, String state, String type, UUID userRepositoryId, String repoName,
                           Instant startedAt) {
}
