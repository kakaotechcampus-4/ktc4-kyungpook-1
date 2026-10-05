package com.gitory.backend.ingest.port;

import com.gitory.backend.ingest.domain.ExclusionReason;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CollectedCommit(String sha, String authorLogin, String authorName, String message,
                              Instant authoredAt, Integer additions, Integer deletions, Integer changedFiles,
                              short parentCount, ExclusionReason exclusionReason) {
}
