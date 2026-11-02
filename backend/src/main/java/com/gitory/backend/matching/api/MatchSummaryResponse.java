package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.MatchSummary;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "기술·경험 필수 조건 중 채운 수로, 학력·경력 같은 일반 조건은 세지 않는다. 모자란 수는 requiredTotal - requiredMet")
public record MatchSummaryResponse(int requiredMet, int requiredTotal) {

    static MatchSummaryResponse from(MatchSummary summary) {

        return new MatchSummaryResponse(summary.requiredMet(), summary.requiredTotal());

    }
}
