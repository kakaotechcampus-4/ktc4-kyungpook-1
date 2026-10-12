package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class InterviewTurnTest {

    private static final Instant NOW = Instant.parse("2026-10-10T01:02:03.123456789Z");
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-10-10T01:02:03.123456Z");

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

    @Test
    @DisplayName("질문을 만들면 카드·번호·칸·종류·글은 받은 그대로 들어가고, 질문한 쪽은 USER 이며 부모·답·결과는 비어 있다")
    void asksWithGivenFieldsAndNothingElse() {

        InterviewTurn turn = InterviewTurn.ask(5L, (short) 2, StarField.A, QuestionType.FOLLOWUP, "  묻는 글  ");

        assertThat(turn.getCardId()).isEqualTo(5L);
        assertThat(turn.getSeq()).isEqualTo((short) 2);
        assertThat(turn.getStarSlot()).isEqualTo(StarField.A);
        assertThat(turn.getQuestionType()).isEqualTo(QuestionType.FOLLOWUP);
        assertThat(turn.getQuestionText()).isEqualTo("  묻는 글  ");
        assertThat(turn.getTriggerSource()).isEqualTo(TriggerSource.USER);
        assertThat(turn.getParentTurnId()).isNull();
        assertThat(turn.getAnswerText()).isNull();
        assertThat(turn.getAnswerSource()).isNull();
        assertThat(turn.getOutcome()).isNull();
        assertThat(turn.getAnsweredAt()).isNull();
        assertThat(turn.isAnswered()).isFalse();

    }

    @Test
    @DisplayName("질문한 시각은 지금 시각에서 µs 아래를 잘라 낸 값이다")
    void asksAtNowInMicros() {

        // 실제 시계는 µs 아래가 0 인 값을 줄 때가 있어 잘라 냈는지 가릴 수 없으므로, 지금 시각을 ns 자리까지 있는 값으로 정해 둔다
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(NOW);

            InterviewTurn turn = InterviewTurn.ask(5L, (short) 1, StarField.T, QuestionType.EVIDENCE_GAP, "질문");

            assertThat(turn.getAskedAt()).isEqualTo(NOW_IN_MICROS);
        }

    }
}
