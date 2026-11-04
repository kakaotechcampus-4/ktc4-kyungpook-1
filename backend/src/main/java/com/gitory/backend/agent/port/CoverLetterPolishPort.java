package com.gitory.backend.agent.port;

public interface CoverLetterPolishPort {

    /**
     * 문항 하나의 지금 글을 진단하고 고쳐 쓴 글을 돌려준다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterPolish polish(CoverLetterPolishRequest request);
}
