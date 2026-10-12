package com.gitory.backend.ingest.domain;

import java.util.List;

public record GroupingMaterial(List<MaterialCommit> commits, List<MaterialPullRequest> pullRequests,
                               List<MaterialIssue> issues) {
}
