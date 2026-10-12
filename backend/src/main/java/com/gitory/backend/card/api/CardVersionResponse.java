package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardDetail;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.VersionSlots;
import com.gitory.backend.card.domain.VersionSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "카드의 현재 버전으로, 빈 칸은 null")
public record CardVersionResponse(
        int versionNo,
        VersionSource source,
        @Schema(description = "이 버전을 마지막으로 저장한 시각") Instant createdAt,
        String situation,
        String task,
        String action,
        String result) {

    static CardVersionResponse from(CardDetail card) {

        VersionSlots slots = card.slots();

        return new CardVersionResponse(slots.versionNo(), card.source(), card.versionSavedAt(),
                slots.textOf(StarField.S), slots.textOf(StarField.T), slots.textOf(StarField.A),
                slots.textOf(StarField.R));

    }
}
