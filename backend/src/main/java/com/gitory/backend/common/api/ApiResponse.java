package com.gitory.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * API 응답의 공통 형식 {@code { data, error }}.
 * 후보 0개·부분 결과·작업 실패 같은 판정은 오류가 아니라 200 + 상태값으로 내린다.
 * HTTP 오류는 인증 실패와 처리할 수 없는 요청에만 쓴다.
 */

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message) {
        return new ApiResponse<>(null, new ApiError(code.name(), message));
    }
}
