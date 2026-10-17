package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.CandidateBoard;
import com.gitory.backend.recommend.domain.CandidateVerdict;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "저장소의 후보 보드. 후보가 없거나 부분 결과여도 오류가 아니라 verdict 로 알립니다.")
public record CandidateBoardResponse(
        @Schema(format = "uuid") String repoId,
        CandidateVerdict verdict,
        @Schema(description = "마지막 수집이 상한이나 요청 제한으로 일부만 읽었는지") boolean partial,
        @Schema(description = "다시 시도할 때까지 기다릴 초. 보드는 이 값을 채우지 않아 늘 null 이고, 기다릴 초는 분석 Job 의 retryAfterSec 에 있다") Integer retryAfterSeconds,
        @Schema(description = "부분 결과일 때 읽은 수와 전체 수. 작성자와 상관없이 센 숫자이고, 전체 개수를 세지 못했으면 null") ReadCoverageResponse readCoverage,
        @Schema(description = "후보가 하나도 없을 때 이유 문장") List<String> emptyReasons,
        List<CandidateResponse> candidates) {

    static CandidateBoardResponse from(CandidateBoard board) {

        String repoId = board.repositoryId().toString();

        return new CandidateBoardResponse(repoId, board.verdict(), board.partial(), null,
                ReadCoverageResponse.from(board.readCoverage()), board.emptyReasons(),
                board.candidates().stream().map(candidate -> CandidateResponse.from(repoId, candidate)).toList());

    }
}
