package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InterviewPreviousTurn(InterviewStarSlot starSlot, String questionText, InterviewTurnOutcome outcome,
                                    String answerText) {
}
