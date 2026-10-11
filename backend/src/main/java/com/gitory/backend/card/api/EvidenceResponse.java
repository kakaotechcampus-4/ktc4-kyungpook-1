package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.StarField;

public record EvidenceResponse(
        StarField field,
        EvidenceType type,
        AuthoredBy authoredBy,
        String sha,
        String url,
        String snippet,
        Integer turnNo) {

    static EvidenceResponse commit(StarField field) {

        return new EvidenceResponse(field, EvidenceType.COMMIT, AuthoredBy.AI, null, null, null, null);

    }

    static EvidenceResponse user(StarField field, EvidenceType type) {

        return new EvidenceResponse(field, type, AuthoredBy.USER, null, null, null, null);

    }
}
