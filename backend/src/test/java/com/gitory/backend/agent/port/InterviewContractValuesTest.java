package com.gitory.backend.agent.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

class InterviewContractValuesTest {

    @ParameterizedTest
    @MethodSource("aiLiterals")
    @DisplayName("값 이름이 AI 스키마(ai/schemas/interview.py)의 값과 같다 — 다르면 AI 가 400 으로 거절한다")
    void hasTheSameValuesAsTheAiSchema(Class<? extends Enum<?>> type, List<String> literals) {

        assertThat(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(literals);

    }

    static Stream<Arguments> aiLiterals() {

        return Stream.of(
                argumentSet("SourceType", InterviewCardSourceType.class,
                        List.of("PR", "COMMIT_CLUSTER", "DIRECT_CARD")),
                argumentSet("StarSlot", InterviewStarSlot.class, List.of("S", "T", "A", "R")),
                argumentSet("Confidence", InterviewConfidence.class, List.of("LOW", "MEDIUM", "HIGH")),
                argumentSet("ReviewState", InterviewReviewState.class,
                        List.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED", "DISMISSED", "PENDING")),
                argumentSet("NextAction", InterviewNextAction.class, List.of("ASK_AGAIN", "COMPLETE")),
                argumentSet("QuestionType", InterviewQuestionType.class,
                        List.of("EVIDENCE_GAP", "FOLLOWUP", "RECALL_AID")));

    }
}
