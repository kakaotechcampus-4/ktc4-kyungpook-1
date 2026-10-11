package com.gitory.backend.consent.infra;

import com.gitory.backend.consent.port.GithubCollectionRepo;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record AiCollectRequest(Long userRepositoryId, GithubCollectionRepo repository,
                        AiCollectActor actor, List<String> branches) {
}
