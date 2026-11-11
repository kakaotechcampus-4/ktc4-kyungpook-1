package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 가 읽게 할 되묻기 답")
public record InterviewReplyRequest(
        @Schema(description = "사용자가 쓴 답으로, 공백뿐이면 안 되고 1,000자까지이며 앞뒤 공백도 그대로 원문으로 남긴다",
                requiredMode = Schema.RequiredMode.REQUIRED) String text,
        @Schema(description = "직접 쓰거나 고친 답은 USER_STATED, 보기를 그대로 고른 답은 USER_SELECTED",
                allowableValues = {"USER_STATED", "USER_SELECTED"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String source) {
}
