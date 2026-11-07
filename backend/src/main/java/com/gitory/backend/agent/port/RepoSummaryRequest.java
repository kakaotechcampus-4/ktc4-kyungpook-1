package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RepoSummaryRequest(long repoId, String owner, String name, String language,
                                 List<String> topLevelFiles, String readmeHead) {
}
