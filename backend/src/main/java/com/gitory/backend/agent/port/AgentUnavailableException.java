package com.gitory.backend.agent.port;

import lombok.Getter;

/**
 * 모델 호출 실패를 나타내며, 메시지에 프롬프트 본문을 넣지 않는다
 * 부르는 쪽이 잠시 뒤 다시 해도 되는 실패와 AI 가 이상한 응답을 준 실패를 가를 수 있게 두 값을 담는다
 */
@Getter
public class AgentUnavailableException extends RuntimeException {

    private final boolean retryable;
    private final boolean invalidResponse;

    public AgentUnavailableException(String message, boolean retryable, boolean invalidResponse, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.invalidResponse = invalidResponse;
    }
}
