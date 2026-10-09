package com.gitory.backend.consent.port;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GithubRepositoryResponse(long id, String name, GithubOwnerResponse owner,
                                       @JsonProperty("private") boolean privateRepository,
                                       String language, String defaultBranch,
                                       Instant createdAt, Instant pushedAt) {
}
