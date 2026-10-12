package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import org.springframework.test.util.ReflectionTestUtils;

/** 질문 엔티티에는 만드는 방법이 아직 없어서 테스트가 칸을 직접 채운다 */
final class InterviewTurnSamples {

    private InterviewTurnSamples() {
    }

    static InterviewTurn open(int seq) {

        return turn(seq, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER, "질문", null, null, null);

    }

    static InterviewTurn turn(int seq, StarField slot, QuestionType questionType, TriggerSource triggerSource,
                              String question, TurnOutcome outcome, String answerText, AnswerSource answerSource) {

        InterviewTurn turn = new InterviewTurn();
        ReflectionTestUtils.setField(turn, "seq", (short) seq);
        ReflectionTestUtils.setField(turn, "starSlot", slot);
        ReflectionTestUtils.setField(turn, "questionType", questionType);
        ReflectionTestUtils.setField(turn, "triggerSource", triggerSource);
        ReflectionTestUtils.setField(turn, "questionText", question);
        ReflectionTestUtils.setField(turn, "outcome", outcome);
        ReflectionTestUtils.setField(turn, "answerText", answerText);
        ReflectionTestUtils.setField(turn, "answerSource", answerSource);

        return turn;

    }
}
