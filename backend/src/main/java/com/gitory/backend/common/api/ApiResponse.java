package com.gitory.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API 응답의 공통 형식 {@code { data, error }}.
 * 후보 0개·부분 결과·작업 실패 같은 판정은 오류가 아니라 200 + 상태값으로 내린다.
 * HTTP 오류는 인증 실패와 처리할 수 없는 요청에만 쓴다.
 */

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "공통 응답. 성공이면 data에 결과·error=null, HTTP 오류이면 data=null·error에 오류를 담습니다.")
public record ApiResponse<T>(
        @Schema(description = "성공 응답 데이터. 오류 응답에서는 null") T data,
        @Schema(description = "오류 정보. 성공 응답에서는 null") ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message) {
        return new ApiResponse<>(null, new ApiError(code.name(), message));
    }
}
