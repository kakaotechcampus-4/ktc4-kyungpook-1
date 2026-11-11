package com.gitory.backend.agent.port;

public interface CoverLetterSuggestionPort {

    /**
     * 보낸 카드와 후보 중 그 문항에 쓸 만한 것을 좋은 순서로 3개까지 돌려주며, 맞는 것이 없으면 빈 목록이다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterSuggestions suggest(CoverLetterSuggestionRequest request);
}
