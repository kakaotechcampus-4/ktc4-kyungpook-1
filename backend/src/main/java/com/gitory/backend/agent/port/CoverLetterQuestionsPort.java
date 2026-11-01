package com.gitory.backend.agent.port;

public interface CoverLetterQuestionsPort {

    /**
     * 보낸 문항마다 질문 목록을 하나씩 문항 순서대로 돌려주고, 한 문항에서 같은 항목은 한 번만 묻는다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterQuestions ask(CoverLetterQuestionsRequest request);
}
