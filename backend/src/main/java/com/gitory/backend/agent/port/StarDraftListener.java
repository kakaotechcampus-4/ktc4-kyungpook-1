package com.gitory.backend.agent.port;

/** STAR 초안을 쓰는 동안 AI 가 보내는 칸 글과 단계를 받는다 */
public interface StarDraftListener {

    /** text 는 늘어난 부분이 아니라 그 칸에 지금까지 쓴 글 전체이고, done 이면 그 바퀴에서 이 칸을 다 쓴 것이다 */
    void slot(int round, StarSlot slot, String text, boolean done);

    void step(StarStep step);
}
