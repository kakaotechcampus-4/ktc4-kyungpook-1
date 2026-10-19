package com.gitory.backend.usage.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "화면이 보내는 사용 지표 한 건")
public record UsageEventRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "허용된 지표 이름. 목록에 없으면 400", example = "repo_selected") String name,
        @Schema(description = "추가 값. 문자열·숫자·불리언·null 만 가능하고 직렬화 2KB 이하") Map<String, Object> props,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "화면에서 이벤트가 난 시각(ISO-8601). 1970~9999년", example = "2026-10-18T01:00:00Z") Instant at,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "이벤트가 난 화면 경로. 255자 이하", example = "/repos") String path) {
}
