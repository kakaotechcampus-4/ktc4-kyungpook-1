package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "질문을 받을 칸으로, 비우면 AI 가 고른다")
public record InterviewAskRequest(
        @Schema(description = "S, T, A, R 중 하나이고, 없으면 고를 수 있는 칸 중 AI 가 고른다",
                allowableValues = {"S", "T", "A", "R"}) String field) {
}
