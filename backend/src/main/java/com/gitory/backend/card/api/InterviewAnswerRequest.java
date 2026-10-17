package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "되묻기 질문에 대한 답")
public record InterviewAnswerRequest(
        @Schema(description = "카드 칸의 글이 될 답으로, 공백뿐이면 안 되고 1,000자까지이며 앞뒤 공백도 그대로 저장한다",
                requiredMode = Schema.RequiredMode.REQUIRED) String text,
        @Schema(description = "직접 쓴 답을 뜻하는 USER_STATED 만 받는다", allowableValues = {"USER_STATED"},
                requiredMode = Schema.RequiredMode.REQUIRED) String source) {
}
