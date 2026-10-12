package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.SavedDraft;
import com.gitory.backend.card.domain.StarField;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "임시 저장 결과. 칸은 저장된 글이고 비운 칸은 null 입니다.")
public record CardDraftResponse(
        @Schema(format = "uuid") String cardId,
        int versionNo,
        String situation,
        String task,
        String action,
        String result,
        @Schema(description = "이 버전의 글을 마지막으로 저장한 시각") Instant savedAt) {

    static CardDraftResponse from(SavedDraft saved) {

        return new CardDraftResponse(
                saved.cardId().toString(),
                saved.versionNo(),
                saved.texts().get(StarField.S),
                saved.texts().get(StarField.T),
                saved.texts().get(StarField.A),
                saved.texts().get(StarField.R),
                saved.savedAt());

    }
}
