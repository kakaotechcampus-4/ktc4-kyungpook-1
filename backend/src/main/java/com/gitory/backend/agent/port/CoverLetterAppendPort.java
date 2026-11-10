package com.gitory.backend.agent.port;

public interface CoverLetterAppendPort {

    /**
     * 지금 글의 문장은 그대로 두고 새 카드의 내용을 한두 문장으로 더한 글을 받으며, AI 가 원래 문장이 바뀌었거나 더한 문장이 없어 되돌렸으면 reverted 가 true 이고 글은 앞 글이다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterAppend append(CoverLetterAppendRequest request);
}
