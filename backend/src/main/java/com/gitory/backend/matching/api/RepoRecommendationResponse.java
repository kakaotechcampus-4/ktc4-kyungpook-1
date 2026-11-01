package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.RepoRecommendationView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record RepoRecommendationResponse(
        @Schema(description = "COMPUTING 은 계산을 기다리거나 하는 중, NONE 은 아직 계산한 적 없음",
                allowableValues = {"COMPUTING", "READY", "FAILED", "NONE"}) String status,
        @Schema(description = "마지막으로 계산을 마친 시각. 계산한 적이 없으면 null") Instant computedAt,
        @Schema(description = "추천 기업. 많아야 3곳이고 순위 순서") List<Company> companies) {

    public record Company(String companyId, String companyName,
                          @Schema(description = "https 로고 주소. 없으면 null") String logoUrl, Posting posting,
                          @Schema(description = "이 레포 카드가 채운 조건의 기술 이름(카드 techs 와 같은 값), 많아야 3개") List<String> techs,
                          @Schema(description = "이 레포 카드가 채웠다고 AI 가 판단한 필수 조건 수") int requiredMet,
                          @Schema(description = "이 레포 카드가 채웠다고 AI 가 판단한 우대 조건 수") int preferredMet) {
    }

    public record Posting(@Schema(description = "대표 공고 id, 매칭 상세 주소에 쓴다") String id, String title,
                          @Schema(description = "마감 시각. 상시 채용이면 null") Instant deadline,
                          @Schema(description = "계산한 뒤 마감되거나 사라진 공고면 true") boolean closed) {
    }

    static RepoRecommendationResponse from(RepoRecommendationView view) {

        return new RepoRecommendationResponse(view.status() == null ? "NONE" : view.status().name(), view.computedAt(),
                view.companies().stream()
                        .map(company -> new Company(company.companyId(), company.companyName(), company.logoUrl(),
                                new Posting(company.posting().id().toString(), company.posting().title(),
                                        company.posting().deadline(), company.posting().closed()),
                                company.techs(), company.requiredMet(), company.preferredMet()))
                        .toList());

    }
}
