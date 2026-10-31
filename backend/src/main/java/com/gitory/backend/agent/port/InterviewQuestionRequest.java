package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InterviewQuestionRequest(long cardId, InterviewCandidate candidate,
                                       List<InterviewMissingSlot> missingSlots, int existingTurnCount,
                                       int maxTurns, InterviewCardSourceType sourceType, InterviewCardKind cardKind,
                                       InterviewStarSlot targetStarSlot, List<InterviewStarSlot> allowedSlots,
                                       List<InterviewPreviousTurn> previousTurns) {
}
