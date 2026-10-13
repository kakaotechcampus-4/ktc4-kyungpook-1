package com.gitory.backend.job.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "후보로 만든 카드와 그 초안 Job")
public record CardCreationResponse(
        @Schema(format = "uuid", description = "카드 초안 Job 의 id 이고 GET /api/jobs/{id} 로 조회") String jobId,
        @Schema(description = "만든 카드 id 이고 보낸 후보 순서대로 1개 이상") List<String> cardIds) {
}
