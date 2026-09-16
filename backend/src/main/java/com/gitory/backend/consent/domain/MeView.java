package com.gitory.backend.consent.domain;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/me} 응답 본문. 프론트의 zod 스키마({@code frontend/src/api/schemas.ts} 의
 * {@code Me})가 단일 출처이고, 필드가 빠지면 프론트가 첫 호출에서 ContractError 로 잡는다.
 *
 * <p>엔티티를 그대로 내보내지 않는다 — {@code ModuleBoundaryTest} 가 웹 계층의 엔티티 의존을
 * 막는다. 토큰이 실릴 자리가 타입에 아예 없다는 점이 더 중요하다.
 */
public record MeView(
        String id,
        String login,
        String avatarUrl,
        Plan plan,
        Github github,
        Stats stats) {

    /** 지금은 한 가지뿐이다. 프론트 계약도 {@code z.enum(['FREE'])} 로 하나만 받는다. */
    public enum Plan {
        FREE
    }

    /**
     * GitHub 연결 상태. "연결 범위 확인 / 연결 해제" 화면이 이 값으로 그려진다.
     *
     * @param lastCollectedAt 마지막 수집 시각. 수집은 ingest 모듈 몫이라 아직 항상 null 이다
     */
    public record Github(
            boolean connected,
            List<String> scopes,
            Instant connectedAt,
            Instant lastCollectedAt) {

        static Github notConnected() {
            return new Github(false, List.of(), null, null);
        }
    }

    /**
     * 마이페이지 숫자 4개.
     *
     * <p>⚠️ 지금은 전부 0 이다. 자리를 채우려고 넣은 가짜 값이 아니라, 카드·후보·인터뷰를
     * 만드는 경로(card · recommend · job)가 아직 없어 <b>모든 사용자의 실제 값이 0</b> 이다.
     * 해당 모듈이 들어오면 그쪽에서 세어 채운다 — consent 가 남의 소유 테이블을 세지 않는다
     * (ARCHITECTURE.md 모듈별 소유 테이블).
     */
    public record Stats(
            int confirmedCards,
            int analyzedRepos,
            int interviewTurns,
            int remainingCandidates) {

        static Stats none() {
            return new Stats(0, 0, 0, 0);
        }
    }
}
