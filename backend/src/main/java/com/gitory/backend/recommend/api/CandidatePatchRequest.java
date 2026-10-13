package com.gitory.backend.recommend.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "후보 수정 요청. status 와 excludedShas 중 하나는 보내야 합니다.")
public record CandidatePatchRequest(
        @Schema(description = "NEW 로 복원하거나 EXCLUDED 로 제외. USED 는 보낼 수 없습니다") String status,
        @Schema(description = "커밋 묶음 후보에서 뺄 커밋의 전체 sha 전부. 목록에 없는 커밋은 포함으로 돌아가고 빈 목록이면 모두 포함입니다") List<String> excludedShas) {
}
