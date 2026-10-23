package com.gitory.backend.agent.port;

public interface InterviewMeasuredSentencePort {

    /** AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다 */
    String write(InterviewMeasuredSentenceRequest request);
}
