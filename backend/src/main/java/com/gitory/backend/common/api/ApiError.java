package com.gitory.backend.common.api;

/** API 오류 응답. message 는 사용자에게 그대로 노출되므로 내부 정보를 담지 않는다. */
public record ApiError(String code, String message) {
}
