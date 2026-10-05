package com.gitory.backend.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** API 오류 응답. message 는 사용자에게 그대로 노출되므로 내부 정보를 담지 않는다. */
@Schema(description = "사용자에게 노출 가능한 오류 코드와 안내 문구")
public record ApiError(
        @Schema(example = "UNAUTHENTICATED") String code,
        @Schema(example = "로그인이 필요합니다.") String message) {
}
