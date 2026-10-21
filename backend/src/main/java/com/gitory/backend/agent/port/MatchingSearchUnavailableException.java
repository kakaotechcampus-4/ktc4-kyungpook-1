package com.gitory.backend.agent.port;

import lombok.Getter;

/**
 * 매칭 검색 호출 실패이며, 로그에 남길 수 있게 AI 오류 코드를 함께 담는다
 * 코드는 AI 가 정한 값이나 호출 쪽 분류 이름뿐이고 요청·응답의 글은 담지 않는다
 */
@Getter
public class MatchingSearchUnavailableException extends AgentUnavailableException {

    private final String errorCode;

    public MatchingSearchUnavailableException(String message, boolean retryable, boolean invalidResponse,
                                              String errorCode, Throwable cause) {
        super(message, retryable, invalidResponse, cause);
        this.errorCode = errorCode;
    }
}
