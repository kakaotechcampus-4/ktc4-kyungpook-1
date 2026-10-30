package com.gitory.backend.coverletter.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "문항의 중심·보조 카드를 바꿀 때 보내는 값")
public record ChooseCardsRequest(
        @Schema(description = "중심 카드 공개 id. 없으면 null", format = "uuid") String primaryCardId,
        @Schema(description = "보조 카드 공개 id. 없으면 null", format = "uuid") String supportingCardId) {
}
