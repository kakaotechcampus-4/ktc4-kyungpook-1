package com.gitory.backend.agent.port;

public interface InterviewQuestionPort {

    /**
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     * 다시 시도해도 되는 실패는 retryable, 응답이 이상한 실패는 invalidResponse 가 true 다
     */
    InterviewQuestionResult nextQuestion(InterviewQuestionRequest request);
}
