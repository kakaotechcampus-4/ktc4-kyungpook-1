package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.JobState;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "분석 Job 접수 결과. 기존 Job을 돌려주면 그 Job의 현재 상태가 담깁니다.")
public record StartedResponse(
        @Schema(format = "uuid") String jobId,
        JobState state,
        @Schema(description = "권장 다음 조회 간격(밀리초)", example = "2000") int pollAfterMs) {
}
