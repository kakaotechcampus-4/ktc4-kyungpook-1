package com.gitory.backend.card.domain;

import java.util.List;

public record SlotContent(
        StarField slot,
        String body,
        EvidenceType evidenceType,
        Confidence confidence,
        Long sourceTurnId,
        List<Long> commitIds) {
}
