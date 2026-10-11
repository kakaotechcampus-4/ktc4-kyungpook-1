package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperienceGroupingRequest(String repositoryId, String targetLogin, List<GroupingCommit> commits,
                                        List<GroupingPullRequest> pullRequests, List<GroupingIssue> issues,
                                        boolean collectionPartial) {
}
