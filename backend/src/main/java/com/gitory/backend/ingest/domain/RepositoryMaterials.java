package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.List;

public record RepositoryMaterials(List<CommitLine> commits, List<PullRequestLine> pullRequests) {

    public record CommitLine(String sha, String title, Instant authoredAt, int files, Integer pullRequestNumber,
                             ExclusionReason excluded) {
    }

    public record PullRequestLine(int number, String title, Instant mergedAt, int commitCount) {
    }
}
