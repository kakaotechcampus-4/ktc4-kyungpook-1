package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.List;

public record MaterialCommit(String sha, String message, String authorLogin, String authorName, Instant authoredAt,
                             int parentCount, Integer additions, Integer deletions, Integer changedFiles,
                             List<MaterialFile> files, Integer pullRequestNumber, List<Integer> issueNumbers) {
}
