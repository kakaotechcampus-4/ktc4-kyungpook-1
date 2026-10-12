package com.gitory.backend.consent.port;

/** 내 정보 화면의 숫자는 카드·저장소·후보에서 세므로 consent 가 직접 읽지 않고 이 인터페이스로 받는다 */
public interface MeStatsPort {

    MeStats of(Long userId);
}
