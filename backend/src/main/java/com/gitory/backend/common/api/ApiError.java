package com.gitory.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** API 오류 응답. message 는 사용자에게 그대로 노출되므로 내부 정보를 담지 않는다. */
@Schema(description = "사용자에게 노출 가능한 오류 코드와 안내 문구")
public record ApiError(
        @Schema(example = "UNAUTHENTICATED") String code,
        @Schema(example = "로그인이 필요합니다.") String message,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "오류마다 덧붙이는 값. RATE_LIMITED 는 retryAfterSec(기다릴 초)를 담고, 덧붙일 값이 없으면 키가 빠진다",
                example = "{\"retryAfterSec\": 42}") Map<String, Object> details) {

    public ApiError(String code, String message) {
        this(code, message, null);
    }
}
