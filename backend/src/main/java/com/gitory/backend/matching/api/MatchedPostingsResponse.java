package com.gitory.backend.matching.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record MatchedPostingsResponse(
        @Schema(description = "지금 확정 카드 글로 필수 기술·경험 조건을 하나 이상 채운다고 판단된 마감 전 공고 수. 확정 카드가 없으면 0") int matchedPostings) {
}
