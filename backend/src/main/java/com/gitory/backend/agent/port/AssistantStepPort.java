package com.gitory.backend.agent.port;

public interface AssistantStepPort {

    /**
     * 사용자가 적은 말과 지금까지 실행한 도구 결과를 보내 AI 가 고른 다음 행동 하나(도구 부르기·사용자에게 묻기·끝내기)를 받는다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    AssistantStepReply next(AssistantStepRequest request);
}
