package com.gitory.backend.agent.port;

/** 자소서 초안을 쓰는 동안 AI 가 보내는 문항별 글과 단계를 받는다 */
public interface CoverLetterDraftsListener {

    /** text 는 그 문항에 지금까지 쓴 글 전체이고, round 는 처음 쓴 글이 0 이며 고쳐 쓸 때마다 하나씩 늘어난다 */
    void text(int questionId, int round, String text);

    void step(CoverLetterDrafts.Step step);
}
