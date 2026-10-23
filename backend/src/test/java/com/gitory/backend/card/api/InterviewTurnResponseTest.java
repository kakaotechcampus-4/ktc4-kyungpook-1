package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.interview.AnswerMode;
import com.gitory.backend.card.domain.interview.AnswerSource;
import com.gitory.backend.card.domain.interview.InterviewAnswer;
import com.gitory.backend.card.domain.interview.InterviewSourceType;
import com.gitory.backend.card.domain.interview.InterviewTurnView;
import com.gitory.backend.card.domain.interview.QuestionType;
import com.gitory.backend.card.domain.interview.TriggerSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewTurnResponseTest {

    @Test
    @DisplayName("질문 번호·칸·출처 종류·질문 종류·질문 글·남은 수·최대 수는 도메인 값 그대로고 근거·보기는 빈 배열이다")
    void copiesDomainValues() {

        InterviewTurnView view = new InterviewTurnView(2, StarField.R, TriggerSource.USER,
                InterviewSourceType.COMMIT_CLUSTER, QuestionType.RECALL_AID, "  결과는 어땠나요?  ", AnswerMode.TEXT, List.of(), null, 1, 3);

        InterviewTurnResponse response = InterviewTurnResponse.from(view);

        assertThat(response.turnNo()).isEqualTo(2);
        assertThat(response.field()).isEqualTo(StarField.R);
        assertThat(response.sourceType()).isEqualTo(InterviewSourceType.COMMIT_CLUSTER);
        assertThat(response.questionType()).isEqualTo(QuestionType.RECALL_AID);
        assertThat(response.question()).isEqualTo("  결과는 어땠나요?  ");
        assertThat(response.remaining()).isEqualTo(1);
        assertThat(response.maxTurns()).isEqualTo(3);
        assertThat(response.found()).isEmpty();
        assertThat(response.missing()).isEmpty();
        assertThat(response.options()).isEmpty();
        assertThat(response.answer()).isNull();

    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"USER, USER_REQUEST", "AUTO, FOLLOW_UP"})
    @DisplayName("저장된 USER 는 USER_REQUEST 로, AUTO 는 FOLLOW_UP 으로 바꿔 askedBy 에 담는다")
    void mapsTriggerSourceToAskedBy(TriggerSource source, AskedBy expected) {

        InterviewTurnView view = new InterviewTurnView(1, StarField.T, source, InterviewSourceType.DIRECT_CARD,
                QuestionType.EVIDENCE_GAP, "질문", AnswerMode.TEXT, List.of(), null, 1, 2);

        assertThat(InterviewTurnResponse.from(view).askedBy()).isEqualTo(expected);

    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"TYPED, USER_STATED", "SELECTED, USER_SELECTED"})
    @DisplayName("저장된 TYPED 답은 USER_STATED 로, SELECTED 답은 USER_SELECTED 로 바꿔 answer.source 에 담는다")
    void mapsAnswerSource(AnswerSource stored, EvidenceType expected) {

        InterviewTurnView view = new InterviewTurnView(1, StarField.T, TriggerSource.USER,
                InterviewSourceType.DIRECT_CARD, QuestionType.EVIDENCE_GAP, "질문",
                AnswerMode.TEXT, List.of(), new InterviewAnswer("  답  ", stored), 1, 2);

        InterviewAnswerResponse answer = InterviewTurnResponse.from(view).answer();

        assertThat(answer.text()).isEqualTo("  답  ");
        assertThat(answer.source()).isEqualTo(expected);

    }
}
