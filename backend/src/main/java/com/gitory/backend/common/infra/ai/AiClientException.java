package com.gitory.backend.common.infra.ai;

/** 응답 원문·외부 예외·헤더를 보관하지 않는 서버 간 호출 오류. */
public final class AiClientException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;
    private final Integer httpStatus;

    public AiClientException(String errorCode, boolean retryable, Integer httpStatus) {
        super("AI 서버 호출 실패 (" + errorCode + ")");
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
    public Integer httpStatus() { return httpStatus; }

    public static AiClientException invalidResponse() {
        return new AiClientException("AI_INVALID_RESPONSE", false, null);
    }
}
