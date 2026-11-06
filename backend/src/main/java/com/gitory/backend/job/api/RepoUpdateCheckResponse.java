package com.gitory.backend.job.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record RepoUpdateCheckResponse(
        @Schema(description = "분석 Job 을 접수한 레포 수. 없으면 0", example = "1") int queued) {
}
