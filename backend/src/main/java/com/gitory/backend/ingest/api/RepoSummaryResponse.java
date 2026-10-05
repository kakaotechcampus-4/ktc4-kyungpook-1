package com.gitory.backend.ingest.api;

import com.gitory.backend.ingest.domain.ContributionLevel;
import com.gitory.backend.ingest.domain.RepositorySummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "분석할 저장소를 고르는 목록의 한 줄. 기여 개수는 아직 세지 않아 0입니다.")
public record RepoSummaryResponse(
        @Schema(format = "uuid", description = "분석 요청 경로의 {id} 로 쓰는 저장소 id") String id,
        String owner,
        String name,
        ContributionResponse contribution,
        int prCount,
        int reviewCount,
        String language,
        @Schema(description = "GitHub 에서 저장소를 만든 시각") Instant activeFrom,
        @Schema(description = "GitHub 에 마지막으로 push 한 시각") Instant activeTo,
        Instant lastAnalyzedAt,
        Integer candidateCount,
        int cardCount,
        boolean recommended) {

    static RepoSummaryResponse from(RepositorySummary repository) {
        return new RepoSummaryResponse(
                repository.publicId().toString(),
                repository.ownerLogin(),
                repository.name(),
                new ContributionResponse(0, 0, 0, ContributionLevel.NONE),
                0,
                0,
                repository.language(),
                repository.activeFrom(),
                repository.activeTo(),
                repository.lastAnalyzedAt(),
                null,
                0,
                false);
    }
}
