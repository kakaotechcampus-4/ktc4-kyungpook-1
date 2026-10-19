package com.gitory.backend.agent.port;

public interface InterviewAnswerReviewPort {

    /**
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     * 답이 모자라다고 판정해도 다시 물을 질문이 없을 수 있어 followUp 은 null 일 수 있다
     */
    InterviewAnswerReviewResult review(InterviewAnswerReviewRequest request);
}
