package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.BoardCandidate;
import com.gitory.backend.recommend.domain.CandidateStatus;
import com.gitory.backend.recommend.domain.CandidateType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "경험 후보 한 개")
public record CandidateResponse(
        @Schema(format = "uuid") String id,
        @Schema(format = "uuid", description = "저장소 id") String repoId,
        CandidateType type,
        CandidateStatus status,
        @Schema(description = "PR 후보는 PR 번호(예: #42), 커밋 묶음은 날짜, 없으면 빈 글자") String ref,
        String title,
        @Schema(description = "추천 이유 한 줄") String reason,
        CandidateMetaResponse meta,
        @Schema(description = "카드감 낮음 표시") boolean weak,
        @Schema(description = "0~1 점수. 없으면 0") double score,
        @Schema(description = "COMMIT_CLUSTER 후보만 커밋 목록이 오고 나머지는 null") List<ClusterCommitResponse> commits,
        @Schema(format = "uuid", description = "이 후보로 만든 카드 id. 없으면 null") String usedByCardId) {

    static CandidateResponse from(String repoId, BoardCandidate candidate) {

        return new CandidateResponse(candidate.id().toString(), repoId, candidate.type(), candidate.status(),
                candidate.ref(), candidate.title(), candidate.reason(), CandidateMetaResponse.from(candidate),
                candidate.weak(), candidate.score().doubleValue(),
                candidate.commits() == null ? null
                        : candidate.commits().stream().map(ClusterCommitResponse::from).toList(),
                candidate.usedByCardId() == null ? null : candidate.usedByCardId().toString());

    }
}
