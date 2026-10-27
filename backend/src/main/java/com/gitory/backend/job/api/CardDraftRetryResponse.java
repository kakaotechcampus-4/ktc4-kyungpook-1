package com.gitory.backend.job.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카드 초안을 다시 만드는 Job")
public record CardDraftRetryResponse(
        @Schema(format = "uuid", description = "카드 초안 Job 의 id 이고 GET /api/jobs/{id} 로 조회") String jobId) {
}
