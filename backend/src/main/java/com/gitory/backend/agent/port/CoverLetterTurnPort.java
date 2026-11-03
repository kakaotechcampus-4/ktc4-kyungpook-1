package com.gitory.backend.agent.port;

public interface CoverLetterTurnPort {

    /**
     * 대화 전체와 문항 재료를 보내 AI 의 다음 말(질문·받아 주기·마무리)과, 사용자가 답했으면 재료로 정리한 답을 받는다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterTurnReply next(CoverLetterTurnRequest request);

    /**
     * 대화에서 받은 답으로 그 문항의 빈 자리만 다시 쓴 글을 받으며, AI 가 다시 쓴 글을 되돌렸으면 reverted 가 true 이고 글은 앞 글이다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterFill fill(CoverLetterFillRequest request);
}
