package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterPostingInput(String company, String title, List<String> responsibilities,
                                      List<Condition> conditions) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Condition(String type, String text, String status, List<Long> metBy) {
    }
}
