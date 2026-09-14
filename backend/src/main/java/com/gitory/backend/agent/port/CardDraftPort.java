package com.gitory.backend.agent.port;

/**
 * {@code card}·{@code interview} 가 LLM 을 보는 유일한 창구다.
 *
 * <p>구현은 {@code agent.infra} 에 있고 모듈 밖에서 참조할 수 없다
 * ({@code ModuleBoundaryTest#infraIsModulePrivate}). 모델을 바꿀 때 손댈 곳이
 * 한 곳이어야 한다 — 실측 자체가 아직 스펙의 {@code gpt-4.1-mini} 가 아닌 모델로 돌아갔고,
 * 모델이 확정되면 같은 프롬프트를 그대로 재실행하는 것이 계획이다.
 */
public interface CardDraftPort {

    /**
     * 근거 묶음으로 STAR 카드 초안을 만든다.
     *
     * <p>구현은 반드시 다음을 지킨다.
     * <ul>
     *   <li>입력에 커밋별 변경량을 포함한다 — 없으면 기여 귀속이 저자 기준으로 무너진다.</li>
     *   <li>초안 뒤 자기검증 단계를 스키마로 강제한다 — 근거 sha 를 입력에서 다시 찾아
     *       실재하지 않으면 제거하고 그 사실을 남긴다.</li>
     *   <li>PR 리뷰 전문을 넣지 않는다 — 토큰 2.2배, 지연 113초, 적중률은 하락했다.</li>
     *   <li>근거를 붙일 수 없는 칸은 비운다. 채우려 들면 지어내기 시작한다.</li>
     * </ul>
     *
     * @throws AgentUnavailableException 모델 호출이 실패했을 때. 호출자는 Job 을 FAILED 로
     *                                   두고 사용자에게 재시도를 안내한다.
     */
    CardDraft draft(DraftRequest request);
}
