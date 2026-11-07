package com.gitory.backend.agent.port;

/** 후보 커밋 근거로 STAR 네 칸 초안을 받는다, 실패하면 AgentUnavailableException 을 던진다 */
public interface StarDraftPort {

    StarDraft draft(StarDraftRequest request);

    /** {@link #draft(StarDraftRequest)} 와 같고, AI 가 칸 글을 쓰거나 단계를 거칠 때마다 listener 로 받는다 */
    default StarDraft draft(StarDraftRequest request, StarDraftListener listener) {

        return draft(request);

    }
}
