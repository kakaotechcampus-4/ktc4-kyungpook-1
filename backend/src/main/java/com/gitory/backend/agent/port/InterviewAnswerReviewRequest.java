package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InterviewAnswerReviewRequest(long cardId, InterviewCardSourceType sourceType,
                                           InterviewCandidate candidate, InterviewCurrentTurn currentTurn,
                                           String answerText, InterviewAnswerSource answerSource,
                                           List<InterviewMissingSlot> otherMissingSlots, int existingTurnCount,
                                           int maxTurns) {
}
