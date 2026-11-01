package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;

import java.util.List;

public record InterviewTurnView(
        int turnNo,
        StarField field,
        TriggerSource triggerSource,
        InterviewSourceType sourceType,
        QuestionType questionType,
        String question,
        String reason,
        AnswerMode answerMode,
        List<String> options,
        TurnOutcome outcome,
        InterviewAnswer answer,
        int remaining,
        int maxTurns) {

    static InterviewTurnView of(InterviewTurn turn, InterviewSourceType sourceType, int maxTurns) {

        InterviewAnswer answer = turn.isAnswered()
                ? new InterviewAnswer(turn.getAnswerText(), turn.getAnswerSource(), turn.getAnswerOriginal())
                : null;

        return new InterviewTurnView(turn.getSeq(), turn.getStarSlot(), turn.getTriggerSource(), sourceType,
                turn.getQuestionType(), turn.getQuestionText(), turn.getChoiceReason(), turn.answerModeOrText(),
                List.copyOf(turn.getSuggestedAnswers()), turn.isClosed() ? turn.getOutcome() : null, answer,
                Math.max(0, maxTurns - turn.getSeq()),
                maxTurns);

    }
}
