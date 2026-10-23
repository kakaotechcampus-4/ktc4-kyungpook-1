package com.gitory.backend.agent.port;

import java.util.List;

/** AI 의 공고 조건 색인을 채우고 정리한다, 실패하면 MatchingIndexUnavailableException 을 던진다 */
public interface JobMatchingIndexPort {

    /** 보낸 공고의 조건을 색인에 넣으며, 같은 공고가 이미 있으면 덮어쓴다 */
    MatchingIndexResult index(List<IndexPosting> postings);

    /** 남길 공고 목록에 없는 공고를 색인에서 모두 지운다 */
    MatchingPruneResult prune(List<Long> keepPostingIds);
}
