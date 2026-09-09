package com.gitory.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 응답의 봉투. 프론트 계약({@code frontend/docs/api-spec.md} §0)이 요구하는 모양이다.
 *
 * <pre>{ "data": T, "error": null } | { "data": null, "error": { "code", "message" } }</pre>
 *
 * <p>실패를 전부 HTTP 4xx/5xx 로 내리지 않는다. {@code EMPTY}·{@code FAILED} 같은
 * <b>판정</b>은 200 + 상태값으로 내려간다 — "후보 0개"는 오류가 아니라 정상적인 결과다
 * (user-flow.md 예외 흐름: "역량 부족"으로 해석하지 않음).
 * 진짜 HTTP 오류는 인증 실패와 처리 자체가 불가능한 요청으로 한정한다.
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
