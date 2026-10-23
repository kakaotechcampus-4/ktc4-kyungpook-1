package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "저장하지 않은 R 문장 미리 보기")
public record MeasuredDraftResponse(
        @Schema(description = "수치를 넣은 R 문장으로, 측정 결과에 숫자가 없으면 null") String sentence) {
}
