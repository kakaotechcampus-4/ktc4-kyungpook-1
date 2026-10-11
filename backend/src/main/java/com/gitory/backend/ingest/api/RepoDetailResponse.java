package com.gitory.backend.ingest.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.gitory.backend.ingest.domain.Disclosure;
import com.gitory.backend.ingest.domain.RepositoryDetail;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "저장소 목록 한 줄과 같은 칸에 분석 전 안내(disclosure)를 더한 저장소 상세")
public record RepoDetailResponse(
        @JsonUnwrapped RepoSummaryResponse summary,
        @Schema(description = "분석 전 안내. reads 는 읽는 것, skips 는 안 읽는 것, estimatedSeconds 는 예상 시간(초)") Disclosure disclosure) {

    static RepoDetailResponse from(RepositoryDetail detail) {
        return new RepoDetailResponse(RepoSummaryResponse.from(detail.summary()), detail.disclosure());
    }
}
