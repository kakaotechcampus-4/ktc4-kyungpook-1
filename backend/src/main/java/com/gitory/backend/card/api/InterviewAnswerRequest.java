package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "되묻기 질문에 대한 답")
public record InterviewAnswerRequest(
        @Schema(description = "답의 종류로, TEXT 는 글 답이고 SKIP 은 넘기기다. 없으면 TEXT 로 본다",
                allowableValues = {"TEXT", "SKIP"}, nullable = true) String kind,
        @Schema(description = "TEXT 일 때 카드 칸의 글이 될 답으로, 공백뿐이면 안 되고 1,000자까지이며 앞뒤 공백도 그대로 저장한다. SKIP 이면 보지 않는다",
                nullable = true) String text,
        @Schema(description = "TEXT 일 때 직접 쓰거나 고친 답은 USER_STATED, 보기를 그대로 고른 답은 USER_SELECTED. SKIP 이면 보지 않는다",
                allowableValues = {"USER_STATED", "USER_SELECTED"}, nullable = true) String source) {
}
