package com.gitory.backend.agent.port;

import lombok.Getter;

/** 공고 색인 호출 실패이며, 요청·응답의 글은 담지 않고 AI 오류 코드만 담는다 */
@Getter
public class MatchingIndexUnavailableException extends AgentUnavailableException {

    private final String errorCode;

    public MatchingIndexUnavailableException(String message, boolean retryable, boolean invalidResponse,
                                             String errorCode, Throwable cause) {
        super(message, retryable, invalidResponse, cause);
        this.errorCode = errorCode;
    }
}
