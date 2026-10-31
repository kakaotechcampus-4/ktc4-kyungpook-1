package com.gitory.backend.agent.port;

import lombok.Getter;

/**
 * 조건 뽑기 호출 실패이며, 같은 요청을 다시 해도 같은 LLM 오류인지 알 수 있게 AI 오류 코드를 함께 담는다
 * 코드는 AI 가 정한 값이나 호출 쪽 분류 이름뿐이고 요청·응답의 글은 담지 않는다
 */
@Getter
public class PostingExtractionUnavailableException extends AgentUnavailableException {

    private static final String LLM_UNAVAILABLE = "LLM_UNAVAILABLE";

    private final String errorCode;

    public PostingExtractionUnavailableException(String message, boolean retryable, boolean invalidResponse,
                                                 String errorCode, Throwable cause) {
        super(message, retryable, invalidResponse, cause);
        this.errorCode = errorCode;
    }

    /** LLM 설정 누락·출력 길이 초과처럼 다시 해도 같은 결과인 AI 실패다 */
    public boolean isPermanentModelFailure() {

        return LLM_UNAVAILABLE.equals(errorCode) && !isRetryable();

    }
}
