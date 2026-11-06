package com.gitory.backend.card.domain;

import java.util.Arrays;
import java.util.Map;

public record VersionSlots(short versionNo, Map<StarField, SlotContent> slots) {

    public String textOf(StarField field) {

        SlotContent slot = slots.get(field);

        return slot == null ? null : slot.body();

    }

    public StarFieldState stateOf(StarField field) {

        SlotContent slot = slots.get(field);

        if (slot == null) {
            return StarFieldState.EMPTY;
        }

        return slot.confidence() == Confidence.LOW ? StarFieldState.NEEDS_REVIEW : StarFieldState.FILLED;

    }

    /** 사용자가 읽지 않아도 확정할 수 있는 글로, 상황과 행동이 차 있고 네 칸 어디에도 확인 필요가 없다 */
    public boolean isAutoConfirmable() {

        return stateOf(StarField.S) == StarFieldState.FILLED
                && stateOf(StarField.A) == StarFieldState.FILLED
                && Arrays.stream(StarField.values()).noneMatch(field -> stateOf(field) == StarFieldState.NEEDS_REVIEW);

    }

    /** 확정 버튼으로 확정할 수 있는 글로, 상황과 행동이 비어 있지 않다 */
    public boolean isConfirmable() {

        return stateOf(StarField.S) != StarFieldState.EMPTY && stateOf(StarField.A) != StarFieldState.EMPTY;

    }
}
