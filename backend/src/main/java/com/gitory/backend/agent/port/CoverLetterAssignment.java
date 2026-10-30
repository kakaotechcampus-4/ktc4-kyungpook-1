package com.gitory.backend.agent.port;

import java.util.List;

public record CoverLetterAssignment(List<Question> assignments, List<Step> steps) {

    public record Question(int questionId, String theme, Long primaryCardId, Long supportingCardId, String reason,
                           List<Considered> considered, String decidedBy) {
    }

    public record Considered(long cardId, String note) {
    }

    public record Step(int seq, String actor, String kind, Integer questionId, Long cardId, String reason) {
    }
}
