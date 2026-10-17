package com.gitory.backend.agent.port;

/** 후보 커밋 근거로 STAR 네 칸 초안을 받는다, 실패하면 AgentUnavailableException 을 던진다 */
public interface StarDraftPort {

    StarDraft draft(StarDraftRequest request);
}
