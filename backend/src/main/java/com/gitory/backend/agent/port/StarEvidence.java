package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StarEvidence(String sha, String message, String authorLogin, String churn, String summary,
                           StarEvidenceSource summarySource, List<String> technicalPoints, String url) {
}
