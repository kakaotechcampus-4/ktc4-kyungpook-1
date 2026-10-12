package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "질문을 받을 칸")
public record InterviewAskRequest(
        @Schema(description = "S, T, A, R 중 하나", allowableValues = {"S", "T", "A", "R"},
                requiredMode = Schema.RequiredMode.REQUIRED) String field) {
}
