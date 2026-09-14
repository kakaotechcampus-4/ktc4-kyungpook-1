package com.gitory.backend.common.api;

/**
 * 오류 분류. 프론트가 분기하는 값이므로 이름을 바꾸면 계약이 깨진다.
 *
 * <p>한국어 문자열을 여기에 넣지 않는다 — 라벨 매핑은 프론트 몫이다(api-spec.md §0).
 */
public enum ErrorCode {

    /** 세션이 없거나 만료됐다. 프론트는 연결 단계로 되돌린다. */
    UNAUTHENTICATED,
    /** 로그인은 됐지만 이 자원의 소유자가 아니다. 매 요청 권한 재검증의 결과다. */
    FORBIDDEN,
    /** 분석 범위에 없는 저장소를 건드렸다 (ADR-0003: 선택한 저장소만 분석한다). */
    OUT_OF_SCOPE,
    /** 요청 자체가 처리 불가능하다 (필수 필드 누락 등). */
    INVALID_REQUEST,
    /** 자원이 없다. */
    NOT_FOUND,
    /** GitHub 권한이 거부·만료됐다. 프론트는 재연결을 안내한다. */
    GITHUB_UNAUTHORIZED,
    /** GitHub 요청 한도를 소진했다. 부분 결과(partial=true)와 함께 쓰인다. */
    GITHUB_RATE_LIMITED,
    /** 확정된 카드를 다시 확정하려는 등, 현재 상태에서 불가능한 전이다. */
    INVALID_STATE,
    /** 예상하지 못한 실패. 사용자에게는 재시도를 안내한다. */
    INTERNAL_ERROR
}
