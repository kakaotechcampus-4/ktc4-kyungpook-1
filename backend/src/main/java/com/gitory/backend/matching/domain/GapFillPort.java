package com.gitory.backend.matching.domain;

import java.util.List;
import java.util.function.Consumer;

/** AI 가 못 채운 조건마다 찾아볼지 정하고 채울 자료를 찾아 제안하며, 사용자의 GitHub 토큰으로 공개 커밋도 검색한다 */
public interface GapFillPort {

    /**
     * 조건의 상태가 바뀔 때마다 그 조건 전체를 onCondition 으로 넘기고, 끝나면 보낸 조건 순서대로 결론을 돌려준다
     * 돌려준 제안의 title·repoName 은 비어 있으며, GitHub 연결이 없거나 AI 가 답하지 못하면 MatchingAiUnavailableException 이다
     */
    List<GapFillCondition> fill(Long userId, GapFillRequest request, Consumer<GapFillCondition> onCondition);
}
