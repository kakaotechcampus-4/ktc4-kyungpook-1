package com.gitory.backend.agent.port;

/** 모델 호출 실패를 나타내며, 메시지에 프롬프트 본문을 넣지 않는다 */
public class AgentUnavailableException extends RuntimeException {

    public AgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
