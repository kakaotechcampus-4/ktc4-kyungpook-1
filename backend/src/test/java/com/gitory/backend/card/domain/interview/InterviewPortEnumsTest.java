package com.gitory.backend.card.domain.interview;

import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewStarSlot;
import com.gitory.backend.card.domain.Confidence;
import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** 카드와 agent 포트가 이름으로 값을 옮기므로, 한쪽에만 값이 늘면 요청을 만들 때 터지기 전에 여기서 먼저 깨진다 */
class InterviewPortEnumsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    @DisplayName("카드 쪽 값과 agent 포트 쪽 값은 이름이 서로 같다")
    void cardAndPortUseTheSameNames(Class<? extends Enum<?>> card, Class<? extends Enum<?>> port) {

        assertThat(namesOf(card)).containsExactlyInAnyOrderElementsOf(namesOf(port));

    }

    static Stream<Arguments> pairs() {

        return Stream.of(
                arguments(Named.of("칸", StarField.class), InterviewStarSlot.class),
                arguments(Named.of("확신도", Confidence.class), InterviewConfidence.class),
                arguments(Named.of("질문 종류", QuestionType.class), InterviewQuestionType.class),
                arguments(Named.of("출처 종류", InterviewSourceType.class), InterviewCardSourceType.class));

    }

    private static List<String> namesOf(Class<? extends Enum<?>> type) {

        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();

    }
}
