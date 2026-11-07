package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MatchJudgementRequest(String companyName, String jobTitle, List<JudgeCondition> conditions,
                                    List<JudgeCard> cards) {
}
