package com.gitory.backend.agent.port;

import java.util.Optional;

public interface RepoSummaryPort {

    /**
     * 저장소의 이름·언어·최상위 파일·README 앞부분으로 AI 가 소개 한 문장을 만들며, 재료가 모자라 못 만들면 빈 값이다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    Optional<String> summarize(RepoSummaryRequest request);
}
