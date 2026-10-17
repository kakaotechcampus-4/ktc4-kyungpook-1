package com.gitory.backend.ingest.port;

import com.gitory.backend.ingest.domain.PartialReason;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CollectedActivity(List<CollectedCommit> commits, List<CollectedPullRequest> pullRequests,
                                List<CollectedIssue> issues, String headSha, PartialReason partialReason) {
}
