package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.CandidateBoard;
import com.gitory.backend.recommend.domain.CandidateVerdict;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "저장소의 후보 보드. 후보가 없거나 부분 결과여도 오류가 아니라 verdict 로 알립니다.")
public record CandidateBoardResponse(
        @Schema(format = "uuid") String repoId,
        CandidateVerdict verdict,
        boolean partial,
        @Schema(description = "다시 시도할 때까지 기다릴 초. 아직 채우지 않아 null") Integer retryAfterSeconds,
        @Schema(description = "어디까지 읽었는지. 아직 채우지 않아 null") ReadCoverageResponse readCoverage,
        @Schema(description = "후보가 하나도 없을 때 이유 문장") List<String> emptyReasons,
        List<CandidateResponse> candidates) {

    static CandidateBoardResponse from(CandidateBoard board) {

        String repoId = board.repositoryId().toString();

        return new CandidateBoardResponse(repoId, board.verdict(), false, null, null, board.emptyReasons(),
                board.candidates().stream().map(candidate -> CandidateResponse.from(repoId, candidate)).toList());

    }
}
