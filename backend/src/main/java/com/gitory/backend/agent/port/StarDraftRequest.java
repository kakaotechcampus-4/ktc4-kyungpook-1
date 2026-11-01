package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StarDraftRequest(String candidateId, String targetLogin, String title, StarSourceType sourceType,
                               String sourceRef, List<StarEvidence> evidence,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String pullRequestBody,
                               @JsonInclude(JsonInclude.Include.NON_NULL) StarMaterials materials) {
}
