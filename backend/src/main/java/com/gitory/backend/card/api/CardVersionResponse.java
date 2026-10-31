package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardDetail;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.VersionContent;
import com.gitory.backend.card.domain.VersionSlots;
import com.gitory.backend.card.domain.VersionSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "카드 버전 하나의 네 칸으로, 빈 칸은 null")
public record CardVersionResponse(
        int versionNo,
        VersionSource source,
        @Schema(description = "이 버전을 마지막으로 저장한 시각") Instant createdAt,
        String situation,
        String task,
        String action,
        String result) {

    static CardVersionResponse from(CardDetail card) {

        return of(card.slots(), card.source(), card.versionSavedAt());

    }

    static CardVersionResponse from(VersionContent version) {

        return of(version.slots(), version.source(), version.savedAt());

    }

    private static CardVersionResponse of(VersionSlots slots, VersionSource source, Instant savedAt) {

        return new CardVersionResponse(slots.versionNo(), source, savedAt,
                slots.textOf(StarField.S), slots.textOf(StarField.T), slots.textOf(StarField.A),
                slots.textOf(StarField.R));

    }
}
