package com.gitory.backend.feedback.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "화면이 보내는 문제 신고 한 건")
public record FeedbackRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "무엇이 안 되는지 적은 글. 앞뒤 공백을 뺀 뒤 1~1000자", example = "카드 초안이 만들어지지 않아요") String message,
        @Schema(description = "신고한 화면 주소(쿼리 문자열 제외). 255자 이하", example = "/repos") String path) {
}
