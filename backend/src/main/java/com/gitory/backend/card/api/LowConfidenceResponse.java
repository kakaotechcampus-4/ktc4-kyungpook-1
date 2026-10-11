package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.StarField;

public record LowConfidenceResponse(
        StarField field,
        String why) {
}
