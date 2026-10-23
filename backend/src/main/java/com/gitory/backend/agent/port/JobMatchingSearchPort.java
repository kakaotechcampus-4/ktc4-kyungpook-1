package com.gitory.backend.agent.port;

/** 확정 카드 문장과 가까운 공고 조건을 찾는다, 실패하면 MatchingSearchUnavailableException 을 던진다 */
public interface JobMatchingSearchPort {

    MatchingSearchResult search(MatchingSearchRequest request);
}
