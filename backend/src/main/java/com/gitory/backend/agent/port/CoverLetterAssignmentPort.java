package com.gitory.backend.agent.port;

public interface CoverLetterAssignmentPort {

    /**
     * 보낸 문항마다 배정을 하나씩 문항 순서대로 돌려주고, 카드는 보낸 카드 중에서만 고른다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterAssignment assign(CoverLetterAssignmentRequest request);
}
