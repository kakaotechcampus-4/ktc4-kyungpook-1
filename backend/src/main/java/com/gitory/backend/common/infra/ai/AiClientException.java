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

    // 연결이 안 되거나 시간이 지난 실패는 응답 상태와 상관없이 다시 해 볼 만하다
    // 400 은 우리 요청이 틀렸고 500 은 AI 가 고장 난 것이라 다시 불러도 같은 결과다
    public boolean temporary() {

        if ("AI_UNAVAILABLE".equals(errorCode)) {
            return true;
        }

        if (httpStatus != null && (httpStatus == 400 || httpStatus == 500)) {
            return false;
        }

        return retryable;

    }

    public static AiClientException invalidResponse() {
        return new AiClientException("AI_INVALID_RESPONSE", false, null);
    }
}
