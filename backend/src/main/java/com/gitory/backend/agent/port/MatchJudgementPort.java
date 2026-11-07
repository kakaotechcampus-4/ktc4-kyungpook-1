package com.gitory.backend.agent.port;

import java.util.List;

public interface MatchJudgementPort {

    /**
     * 공고 하나의 조건마다 그 조건을 실제로 채우는 카드를 모두 고르며, 돌려준 목록에 없는 (카드, 조건) 짝은 못 채움이다
     * AI 를 부르지 못했거나 응답이 보낸 조건·카드와 맞지 않으면 AgentUnavailableException 을 던진다
     */
    List<JudgedCondition> judge(MatchJudgementRequest request);
}
