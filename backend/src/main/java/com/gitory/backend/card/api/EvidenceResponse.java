package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.EvidenceType;
import com.gitory.backend.card.domain.StarField;
import io.swagger.v3.oas.annotations.media.Schema;

public record EvidenceResponse(
        StarField field,
        EvidenceType type,
        AuthoredBy authoredBy,
        String sha,
        String url,
        String snippet,
        @Schema(description = "되묻기 질문에 답해 칸의 글이 된 근거에만 그 질문 번호가 있고 그 밖에는 null") Integer turnNo) {

    static EvidenceResponse commit(StarField field) {

        return new EvidenceResponse(field, EvidenceType.COMMIT, AuthoredBy.AI, null, null, null, null);

    }

    static EvidenceResponse user(StarField field, EvidenceType type, Integer turnNo) {

        return new EvidenceResponse(field, type, AuthoredBy.USER, null, null, null, turnNo);

    }
}
