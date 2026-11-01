package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "다듬을 경험 카드 답")
public record PolishedDraftRequest(
        @Schema(description = "사용자가 쓴 답으로, 1,000자까지", requiredMode = Schema.RequiredMode.REQUIRED) String text) {
}
