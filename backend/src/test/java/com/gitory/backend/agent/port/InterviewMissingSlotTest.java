package com.gitory.backend.agent.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewMissingSlotTest {

    @Test
    @DisplayName("확신도가 없는 칸은 만들 수 없다 — AI 가 null 확신도를 400 으로 거절한다")
    void refusesNullConfidence() {

        assertThatThrownBy(() -> new InterviewMissingSlot(InterviewStarSlot.T, 1, null, null, null, List.of()))
                .isInstanceOf(NullPointerException.class);

    }

    @Test
    @DisplayName("확신도가 있으면 본문이 비어 있어도 칸을 만들 수 있다")
    void acceptsEmptyBodyWithConfidence() {

        InterviewMissingSlot slot = new InterviewMissingSlot(InterviewStarSlot.T, 1, null, InterviewConfidence.LOW, null,
                List.of());

        assertThat(slot.confidence()).isEqualTo(InterviewConfidence.LOW);
        assertThat(slot.body()).isNull();

    }
}
