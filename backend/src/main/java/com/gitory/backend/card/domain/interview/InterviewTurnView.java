package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;

public record InterviewTurnView(
        int turnNo,
        StarField field,
        TriggerSource triggerSource,
        InterviewSourceType sourceType,
        QuestionType questionType,
        String question,
        InterviewAnswer answer,
        int remaining,
        int maxTurns) {

    static InterviewTurnView of(InterviewTurn turn, InterviewSourceType sourceType, int maxTurns) {

        InterviewAnswer answer = turn.isAnswered()
                ? new InterviewAnswer(turn.getAnswerText(), turn.getAnswerSource())
                : null;

        return new InterviewTurnView(turn.getSeq(), turn.getStarSlot(), turn.getTriggerSource(), sourceType,
                turn.getQuestionType(), turn.getQuestionText(), answer, Math.max(0, maxTurns - turn.getSeq()),
                maxTurns);

    }
}
