package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "'재 봤어요' 에 쓴 측정 결과")
public record MeasuredDraftRequest(
        @Schema(description = "자유롭게 쓴 측정 결과로, 1,000자까지", requiredMode = Schema.RequiredMode.REQUIRED) String text) {
}
