package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardMetadata;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "고친 뒤의 제목·기간. 기간이 없으면 period 는 null 입니다.")
public record CardMetadataResponse(
        @Schema(format = "uuid") String cardId,
        String title,
        String period,
        @Schema(description = "카드 내용을 마지막으로 바꾼 시각") Instant updatedAt) {

    static CardMetadataResponse from(CardMetadata metadata) {

        return new CardMetadataResponse(
                metadata.cardId().toString(),
                metadata.title(),
                metadata.period(),
                metadata.updatedAt());

    }
}
