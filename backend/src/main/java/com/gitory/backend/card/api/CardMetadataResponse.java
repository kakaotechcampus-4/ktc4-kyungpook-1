package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardMetadata;
import com.gitory.backend.card.domain.CardTheme;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "고친 뒤의 제목·기간·문항 유형. 기간이 없으면 period 는 null 입니다.")
public record CardMetadataResponse(
        @Schema(format = "uuid") String cardId,
        String title,
        String period,
        @Schema(description = "경험 카드의 자소서 문항 유형, 없으면 빈 배열") List<CardTheme> themes,
        @Schema(description = "카드 내용을 마지막으로 바꾼 시각") Instant updatedAt) {

    static CardMetadataResponse from(CardMetadata metadata) {

        return new CardMetadataResponse(
                metadata.cardId().toString(),
                metadata.title(),
                metadata.period(),
                metadata.themes(),
                metadata.updatedAt());

    }
}
