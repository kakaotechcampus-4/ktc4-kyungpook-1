package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GroupingCommit(String sha, Long repositoryId, String message, String authorLogin, String authorName,
                             String authorEmail, OffsetDateTime authoredAt, int parentCount, Integer additions,
                             Integer deletions, Integer changedFiles, List<GroupingFile> files,
                             Integer pullRequestNumber, List<Integer> issueNumbers, Boolean isExcluded,
                             GroupingExclusionReason exclusionReason) {
}
