package com.gitory.backend.job.domain;

import java.time.Instant;
import java.util.UUID;

public record RepoUpdateRow(UUID repoId, String repoName, int cardCount, Instant finishedAt) {
}
