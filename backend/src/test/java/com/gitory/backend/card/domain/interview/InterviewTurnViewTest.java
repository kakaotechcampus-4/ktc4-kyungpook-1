package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewTurnViewTest {

    @Test
    @DisplayName("질문 번호는 seq, 칸·질문 종류·누가 물었나·질문 글은 저장된 값 그대로이고 출처 종류는 받은 값이다")
    void copiesStoredValues() {

        InterviewTurn turn = InterviewTurnSamples.turn(2, StarField.A, QuestionType.FOLLOWUP, TriggerSource.AUTO,
                "  이어서 물어볼게요  ", null, null, null);

        InterviewTurnView view = InterviewTurnView.of(turn, InterviewSourceType.PR, 2);

        assertThat(view.turnNo()).isEqualTo(2);
        assertThat(view.field()).isEqualTo(StarField.A);
        assertThat(view.questionType()).isEqualTo(QuestionType.FOLLOWUP);
        assertThat(view.triggerSource()).isEqualTo(TriggerSource.AUTO);
        assertThat(view.question()).isEqualTo("  이어서 물어볼게요  ");
        assertThat(view.sourceType()).isEqualTo(InterviewSourceType.PR);

    }

    @ParameterizedTest
    @EnumSource(AnswerSource.class)
    @DisplayName("결과가 ANSWERED 인 질문은 저장된 답 글과 출처를 answer 로 담는다")
    void carriesAnswerOfAnsweredTurn(AnswerSource source) {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", TurnOutcome.ANSWERED, "  앞뒤 공백까지 쓴 답  ", source);

        InterviewTurnView view = InterviewTurnView.of(turn, InterviewSourceType.DIRECT_CARD, 2);

        assertThat(view.answer()).isEqualTo(new InterviewAnswer("  앞뒤 공백까지 쓴 답  ", source, null));

    }

    @ParameterizedTest
    @EnumSource(value = TurnOutcome.class, names = "ANSWERED", mode = EnumSource.Mode.EXCLUDE)
    @NullSource
    @DisplayName("결과가 ANSWERED 가 아니거나 비어 있으면 답 글이 남아 있어도 answer 는 null 이다")
    void hasNoAnswerUnlessAnswered(TurnOutcome outcome) {

        InterviewTurn turn = InterviewTurnSamples.turn(1, StarField.T, QuestionType.EVIDENCE_GAP, TriggerSource.USER,
                "질문", outcome, "남은 답 글", AnswerSource.TYPED);

        assertThat(InterviewTurnView.of(turn, InterviewSourceType.DIRECT_CARD, 2).answer()).isNull();

    }

    @Test
    @DisplayName("답하지 않은 질문의 answer 는 null 이다")
    void hasNoAnswerForOpenTurn() {

        InterviewTurnView view = InterviewTurnView.of(InterviewTurnSamples.open(1), InterviewSourceType.DIRECT_CARD, 2);

        assertThat(view.answer()).isNull();

    }

    @ParameterizedTest(name = "최대 {0}개, {1}번 질문 → 남은 {2}개")
    @CsvSource({
            "2, 1, 1",
            "2, 2, 0",
            "2, 3, 0",
            "3, 1, 2",
            "3, 2, 1",
            "3, 3, 0",
            "3, 4, 0",
            "1, 1, 0"
    })
    @DisplayName("남은 질문 수는 최대 질문 수에서 질문 번호를 뺀 값이고 0 아래로 내려가지 않으며, 최대 질문 수는 받은 값 그대로다")
    void remainingFollowsGivenMaxTurns(int maxTurns, int seq, int expectedRemaining) {

        InterviewTurnView view = InterviewTurnView.of(InterviewTurnSamples.open(seq), InterviewSourceType.DIRECT_CARD,
                maxTurns);

        assertThat(view.remaining()).isEqualTo(expectedRemaining);
        assertThat(view.maxTurns()).isEqualTo(maxTurns);

    }
}
