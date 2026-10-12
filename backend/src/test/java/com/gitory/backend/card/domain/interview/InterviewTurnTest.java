package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewTurnTest {

    @Test
    @DisplayName("결과가 ANSWERED 인 질문은 답한 질문이다")
    void answeredWhenOutcomeIsAnswered() {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", TurnOutcome.ANSWERED, "답", AnswerSource.TYPED);

        assertThat(turn.isAnswered()).isTrue();

    }

    @ParameterizedTest
    @EnumSource(value = TurnOutcome.class, names = "ANSWERED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("결과가 LATER·SKIPPED·INSUFFICIENT 인 질문은 답 글이 남아 있어도 답한 질문이 아니다")
    void notAnsweredForOtherOutcomes(TurnOutcome outcome) {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", outcome, "남은 답 글", AnswerSource.TYPED);

        assertThat(turn.isAnswered()).isFalse();

    }

    @Test
    @DisplayName("결과가 비어 있는 질문은 답 글이 남아 있어도 답한 질문이 아니다")
    void notAnsweredWithoutOutcome() {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", null, "남은 답 글", AnswerSource.TYPED);

        assertThat(turn.isAnswered()).isFalse();

    }
}
