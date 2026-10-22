package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("답하면 글은 받은 그대로 담기고 출처는 TYPED, 결과는 ANSWERED 가 되며 질문 쪽 칸은 하나도 바뀌지 않는다")
    void answersWithTheTextAsGiven() {

        InterviewTurn turn = InterviewTurn.ask(5L, (short) 2, StarField.A, QuestionType.FOLLOWUP, "묻는 글");

        turn.answer("  쓴 그대로의 답  ", AnswerSource.TYPED);

        assertThat(turn.getAnswerText()).isEqualTo("  쓴 그대로의 답  ");
        assertThat(turn.getAnswerSource()).isEqualTo(AnswerSource.TYPED);
        assertThat(turn.getOutcome()).isEqualTo(TurnOutcome.ANSWERED);
        assertThat(turn.isAnswered()).isTrue();
        assertThat(turn.getAnsweredAt()).isNotNull();
        assertThat(turn.getCardId()).isEqualTo(5L);
        assertThat(turn.getSeq()).isEqualTo((short) 2);
        assertThat(turn.getStarSlot()).isEqualTo(StarField.A);
        assertThat(turn.getQuestionType()).isEqualTo(QuestionType.FOLLOWUP);
        assertThat(turn.getQuestionText()).isEqualTo("묻는 글");
        assertThat(turn.getTriggerSource()).isEqualTo(TriggerSource.USER);
        assertThat(turn.getParentTurnId()).isNull();

    }

    @ParameterizedTest
    @EnumSource(value = TurnOutcome.class, names = {"LATER", "INSUFFICIENT"})
    @DisplayName("결과가 LATER·INSUFFICIENT 로 남은 질문은 아직 열려 있어 답하면 남아 있던 글을 새 글로 바꾸고 ANSWERED 가 된다")
    void answersAnUnfinishedTurnOverItsLeftoverText(TurnOutcome outcome) {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", outcome, "남아 있던 글", AnswerSource.SELECTED);

        turn.answer("새 답", AnswerSource.TYPED);

        assertThat(turn.getAnswerText()).isEqualTo("새 답");
        assertThat(turn.getAnswerSource()).isEqualTo(AnswerSource.TYPED);
        assertThat(turn.getOutcome()).isEqualTo(TurnOutcome.ANSWERED);

    }

    @ParameterizedTest
    @EnumSource(value = TurnOutcome.class, names = {"LATER", "INSUFFICIENT"})
    @DisplayName("답한 시각이 남아 있어도 결과가 열린 질문이면 답을 받고 답한 시각을 새로 남긴다")
    void answersAnUnfinishedTurnThatKeepsAnAnsweredAt(TurnOutcome outcome) {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", outcome, "남아 있던 글", AnswerSource.SELECTED);
        Instant leftover = Instant.parse("2026-01-01T00:00:00Z");
        ReflectionTestUtils.setField(turn, "answeredAt", leftover);

        turn.answer("새 답", AnswerSource.TYPED);

        assertThat(turn.getOutcome()).isEqualTo(TurnOutcome.ANSWERED);
        assertThat(turn.getAnswerText()).isEqualTo("새 답");
        assertThat(turn.getAnsweredAt()).isAfter(leftover);

    }

    @Test
    @DisplayName("이미 답한 질문에 답을 또 담으려 하면 같은 글이어도 예외가 나고 처음 답은 그대로다")
    void rejectsAnsweringAnAnsweredTurnAgain() {

        InterviewTurn turn = InterviewTurn.ask(5L, (short) 2, StarField.A, QuestionType.FOLLOWUP, "묻는 글");
        turn.answer("처음 답", AnswerSource.TYPED);
        Instant answeredAt = turn.getAnsweredAt();

        assertThatThrownBy(() -> turn.answer("처음 답", AnswerSource.TYPED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> turn.answer("바꾼 답", AnswerSource.TYPED)).isInstanceOf(IllegalStateException.class);

        assertThat(turn.getAnswerText()).isEqualTo("처음 답");
        assertThat(turn.getAnsweredAt()).isEqualTo(answeredAt);

    }

    @ParameterizedTest
    @EnumSource(value = TurnOutcome.class, names = {"ANSWERED", "SKIPPED", "MEASURED", "NOT_MEASURED"})
    @DisplayName("결과가 정해진 질문(답함·넘김·측정)은 답하거나 넘기려 하면 예외가 나고 결과가 그대로다")
    void rejectsChangingAClosedTurn(TurnOutcome outcome) {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.R, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", outcome, "남은 답 글", AnswerSource.TYPED);

        assertThatThrownBy(() -> turn.answer("새 답", AnswerSource.TYPED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(turn::skip).isInstanceOf(IllegalStateException.class);

        assertThat(turn.getOutcome()).isEqualTo(outcome);
        assertThat(turn.getAnswerText()).isEqualTo("남은 답 글");

    }

    @Test
    @DisplayName("넘기면 답 글·출처 없이 결과가 SKIPPED 가 되고 넘긴 시각이 남는다")
    void skipLeavesNoAnswer() {

        InterviewTurn turn = InterviewTurn.ask(5L, (short) 1, StarField.T, QuestionType.EVIDENCE_GAP, "질문");

        turn.skip();

        assertThat(turn.getOutcome()).isEqualTo(TurnOutcome.SKIPPED);
        assertThat(turn.getAnswerText()).isNull();
        assertThat(turn.getAnswerSource()).isNull();
        assertThat(turn.getAnsweredAt()).isNotNull();
        assertThat(turn.isClosed()).isTrue();

    }

    @Test
    @DisplayName("보기에서 고른 답은 출처가 SELECTED 로 남는다")
    void selectedAnswerKeepsSelectedSource() {

        InterviewTurn turn = InterviewTurn.ask(5L, (short) 1, StarField.T, QuestionType.EVIDENCE_GAP, "질문");

        turn.answer("고른 답", AnswerSource.SELECTED);

        assertThat(turn.getAnswerSource()).isEqualTo(AnswerSource.SELECTED);
        assertThat(turn.getOutcome()).isEqualTo(TurnOutcome.ANSWERED);

    }

    @Test
    @DisplayName("답한 시각은 지금 시각에서 µs 아래를 잘라 낸 값이다")
    void answersAtNowInMicros() {

        InterviewTurn turn = InterviewTurn.ask(5L, (short) 1, StarField.T, QuestionType.EVIDENCE_GAP, "질문");

        // 실제 시계는 µs 아래가 0 인 값을 줄 때가 있어 잘라 냈는지 가릴 수 없으므로, 지금 시각을 ns 자리까지 있는 값으로 정해 둔다
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(NOW);

            turn.answer("답", AnswerSource.TYPED);

            assertThat(turn.getAnsweredAt()).isEqualTo(NOW_IN_MICROS);
        }

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
