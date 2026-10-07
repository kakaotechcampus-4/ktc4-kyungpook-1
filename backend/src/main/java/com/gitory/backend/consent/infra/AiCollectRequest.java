package com.gitory.backend.consent.infra;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitory.backend.consent.port.GithubCollectionRepo;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
record AiCollectRequest(Long userRepositoryId, GithubCollectionRepo repository,
                        AiCollectActor actor, List<String> branches, Instant since) {
}
