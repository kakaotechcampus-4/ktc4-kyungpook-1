package com.gitory.backend.agent.port;

public interface ExperienceGroupingPort {

    /**
     * 돌려준 후보 커밋은 모두 보낸 커밋이고 한 커밋은 한 후보에만 들어 있다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    ExperienceGroupingResponse group(ExperienceGroupingRequest request);
}
