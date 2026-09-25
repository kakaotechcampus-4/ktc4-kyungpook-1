package com.gitory.backend.common.api;

/** API 오류 코드. 프론트가 이 값에 따라 화면을 다르게 처리하므로 이름을 바꾸면 계약 변경이 된다. */
public enum ErrorCode {

    UNAUTHENTICATED,
    FORBIDDEN,
    OUT_OF_SCOPE,
    INVALID_REQUEST,
    IDEMPOTENCY_KEY_MISMATCH,
    NOT_FOUND,
    GITHUB_UNAUTHORIZED,
    GITHUB_RATE_LIMITED,
    INVALID_STATE,
    INTERNAL_ERROR
}
