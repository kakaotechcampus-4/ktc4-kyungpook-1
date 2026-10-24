package com.gitory.backend.card.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "되묻기 질문에 대한 답")
public record InterviewAnswerRequest(
        @Schema(description = "답의 종류로, TEXT 는 글 답, MEASURED 는 '재 봤어요', NOT_MEASURED 는 '측정 안 했어요', SKIP 은 넘기기다. 없으면 TEXT 로 본다",
                allowableValues = {"TEXT", "MEASURED", "NOT_MEASURED", "SKIP"}, nullable = true) String kind,
        @Schema(description = "카드 칸의 글이 될 답으로, 공백뿐이면 안 되고 1,000자까지이며 앞뒤 공백도 그대로 저장한다. TEXT·MEASURED 는 필수, NOT_MEASURED 는 있어도 없어도 되고, SKIP 이면 보지 않는다",
                nullable = true) String text,
        @Schema(description = "text 가 있을 때 직접 쓰거나 고친 답은 USER_STATED, 보기를 그대로 고른 답은 USER_SELECTED",
                allowableValues = {"USER_STATED", "USER_SELECTED"}, nullable = true) String source) {
}
