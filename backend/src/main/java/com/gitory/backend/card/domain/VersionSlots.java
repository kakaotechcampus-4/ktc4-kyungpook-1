package com.gitory.backend.card.domain;

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
}
