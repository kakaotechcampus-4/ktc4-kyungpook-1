package com.gitory.backend.agent.port;

public interface CoverLetterDraftsPort {

    /**
     * 보낸 문항마다 초안을 하나씩 문항 순서대로 돌려준다
     * AI 를 부르지 못했거나 응답이 계약과 다르면 AgentUnavailableException 을 던진다
     */
    CoverLetterDrafts write(CoverLetterDraftsRequest request);

    /** {@link #write(CoverLetterDraftsRequest)} 와 같고, AI 가 글을 쓰거나 단계를 거칠 때마다 listener 로 받는다 */
    default CoverLetterDrafts write(CoverLetterDraftsRequest request, CoverLetterDraftsListener listener) {

        return write(request);

    }
}
