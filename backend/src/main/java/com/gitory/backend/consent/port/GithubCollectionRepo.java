package com.gitory.backend.consent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GithubCollectionRepo(long githubRepoId, String ownerLogin, String name, String defaultBranch) {
}
