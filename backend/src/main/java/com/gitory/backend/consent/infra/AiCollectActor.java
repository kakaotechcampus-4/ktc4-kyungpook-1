package com.gitory.backend.consent.infra;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record AiCollectActor(String githubLogin, List<String> knownEmails) {
}
