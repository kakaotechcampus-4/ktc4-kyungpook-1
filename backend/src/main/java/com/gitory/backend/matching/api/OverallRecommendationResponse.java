package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.OverallRecommendationView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record OverallRecommendationResponse(
        @Schema(description = "COMPUTING 은 계산 중, FAILED 는 실패했거나 계산이 멈춤(다시 요청하면 새로 계산), NONE 은 아직 계산한 적 없음",
                allowableValues = {"COMPUTING", "READY", "FAILED", "NONE"}) String status,
        @Schema(description = "마지막으로 계산을 마친 시각. 계산한 적이 없으면 null") Instant computedAt,
        @Schema(description = "계산한 뒤 확정 카드나 인적사항이 바뀌었으면 true. 새 공고는 넣지 않는다") boolean stale,
        @Schema(description = "COMPUTING 일 때만 확인한 공고 수와 전체 수, 그 밖에는 null") RecommendationProgress progress,
        @Schema(description = "추천 기업. 많아야 5곳이고 순위 순서이며, 계산 중이거나 실패해도 지난 결과를 담는다") List<RankedCompany> companies) {

    public record RecommendationProgress(int done, int total) {
    }

    public record RankedCompany(String companyId, String companyName,
                                @Schema(description = "https 로고 주소. 없으면 null") String logoUrl,
                                RepoRecommendationResponse.Posting posting,
                                @Schema(description = "모든 확정 카드로 채웠다고 AI 가 판단한 필수 조건 수") int requiredMet,
                                @Schema(description = "대표 공고의 필수 조건 수(학력·경력 같은 일반 조건 제외)") int requiredTotal,
                                @Schema(description = "채웠다고 AI 가 판단한 우대 조건 수") int preferredMet,
                                @Schema(description = "대표 공고의 우대 조건 수(일반 조건 제외)") int preferredTotal) {
    }

    static OverallRecommendationResponse from(OverallRecommendationView view) {

        return new OverallRecommendationResponse(view.status() == null ? "NONE" : view.status().name(),
                view.computedAt(), view.stale(),
                view.progress() == null ? null
                        : new RecommendationProgress(view.progress().done(), view.progress().total()),
                view.companies().stream()
                        .map(company -> new RankedCompany(company.companyId(), company.companyName(),
                                company.logoUrl(), new RepoRecommendationResponse.Posting(
                                        company.posting().id().toString(), company.posting().title(),
                                        company.posting().deadline(), company.posting().closed()),
                                company.requiredMet(), company.requiredTotal(), company.preferredMet(),
                                company.preferredTotal()))
                        .toList());

    }
}
