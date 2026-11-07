package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.RepoRecommendationLive;
import com.gitory.backend.matching.domain.RepoRecommendationView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record RepoRecommendationResponse(
        @Schema(description = "COMPUTING 은 계산을 기다리거나 하는 중, NONE 은 계산한 적도 카드도 없음",
                allowableValues = {"COMPUTING", "READY", "FAILED", "NONE"}) String status,
        @Schema(description = "마지막으로 계산을 마친 시각. 계산한 적이 없으면 null") Instant computedAt,
        @Schema(description = "추천 기업. 많아야 3곳이고 순위 순서") List<Company> companies,
        @Schema(description = "카드가 바뀌어 다시 계산이 예약돼 있고 아직 시작하지 않았으면 true. 계산을 시작하면 false 가 되고, 계산하는 중에 카드가 또 바뀌면 다시 true") boolean queued,
        @Schema(description = "가장 최근 계산에서 후보 회사를 하나씩 확인한 과정. 서버 메모리에만 있어 계산이 끝난 뒤에도 남지만 서버가 다시 뜨면 사라지고, 새 계산이 후보를 정하는 동안에도 비어 있으며, 없으면 null") Live live) {

    public record Company(String companyId, String companyName,
                          @Schema(description = "https 로고 주소. 없으면 null") String logoUrl, Posting posting,
                          @Schema(description = "이 레포 카드가 채운 조건의 기술 이름(카드 techs 와 같은 값), 많아야 3개") List<String> techs,
                          @Schema(description = "이 레포 카드가 채웠다고 AI 가 판단한 필수 조건 수") int requiredMet,
                          @Schema(description = "이 레포 카드가 채웠다고 AI 가 판단한 우대 조건 수") int preferredMet,
                          @Schema(description = "바로 앞 계산 결과에 없던 기업이면 true. 앞 계산 결과가 없으면 모두 false") boolean fresh) {
    }

    public record Posting(@Schema(description = "대표 공고 id, 매칭 상세 주소에 쓴다") String id, String title,
                          @Schema(description = "마감 시각. 상시 채용이면 null") Instant deadline,
                          @Schema(description = "계산한 뒤 마감되거나 사라진 공고면 true") boolean closed) {
    }

    public record Live(@Schema(description = "계산에 쓴 카드 수. 네 칸이 모두 빈 카드는 세지 않는다") int cardCount,
                       @Schema(description = "카드에서 모은 기술 이름(카드 techs 와 같은 값), 많아야 12개") List<String> techs,
                       @Schema(description = "후보 회사. 1단계가 고른 순서이고 많아야 10곳") List<LiveCandidate> candidates,
                       @Schema(description = "추천 저장까지 마쳤으면 true") boolean finished) {
    }

    public record LiveCandidate(String companyId, String companyName,
                                @Schema(description = "https 로고 주소. 없으면 null") String logoUrl,
                                LivePosting posting,
                                @Schema(description = "대표 공고에서 이 레포 카드 기술과 겹친 필수·우대 조건 수. 0 이면 AI 에게 묻지 않고 건너뛴다") int overlap,
                                @Schema(description = "WAITING 은 차례를 기다림, CHECKING 은 AI 가 확인 중, DONE 은 확인을 마침",
                                        allowableValues = {"WAITING", "CHECKING", "DONE"}) String state,
                                @Schema(description = "AI 판단을 받지 못했으면 true. 이때 state 는 DONE 이고 hits 는 빈 배열") boolean failed,
                                @Schema(description = "이 레포 카드가 채웠다고 AI 가 판단한 조건. 필수가 먼저이고 공고에 적힌 순서이며, 확인을 마치기 전에는 빈 배열") List<LiveHit> hits) {
    }

    public record LivePosting(@Schema(description = "대표 공고 id, 매칭 상세 주소에 쓴다") String id, String title,
                              @Schema(description = "마감 시각. 상시 채용이면 null") Instant deadline) {
    }

    public record LiveHit(@Schema(description = "REQUIRED 는 필수 조건, PREFERRED 는 우대 조건",
                                  allowableValues = {"REQUIRED", "PREFERRED"}) String type,
                          @Schema(description = "조건 글. 80자를 넘으면 80자에서 자른다") String text,
                          @Schema(description = "그 조건을 채운 카드 가운데 첫 카드의 제목. 가리기 전 제목이다") String cardTitle) {
    }

    static RepoRecommendationResponse from(RepoRecommendationView view) {

        return new RepoRecommendationResponse(view.status() == null ? "NONE" : view.status().name(), view.computedAt(),
                view.companies().stream()
                        .map(company -> new Company(company.companyId(), company.companyName(), company.logoUrl(),
                                new Posting(company.posting().id().toString(), company.posting().title(),
                                        company.posting().deadline(), company.posting().closed()),
                                company.techs(), company.requiredMet(), company.preferredMet(), company.fresh()))
                        .toList(),
                view.queued(), view.live() == null ? null : liveOf(view.live()));

    }

    private static Live liveOf(RepoRecommendationLive live) {

        return new Live(live.cardCount(), live.techs(),
                live.candidates().stream()
                        .map(candidate -> new LiveCandidate(candidate.companyId(), candidate.companyName(),
                                candidate.logoUrl(),
                                new LivePosting(candidate.posting().id().toString(), candidate.posting().title(),
                                        candidate.posting().deadline()),
                                candidate.overlap(), candidate.state().name(), candidate.failed(),
                                candidate.hits().stream()
                                        .map(hit -> new LiveHit(hit.type().name(), hit.text(), hit.cardTitle()))
                                        .toList()))
                        .toList(),
                live.finished());

    }
}
