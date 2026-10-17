package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.DropReason;
import com.gitory.backend.card.domain.StarField;

public record DroppedFieldResponse(
        StarField field,
        DropReason reason) {
}
