package com.gitory.backend.consent.port;

/** 탈퇴 전에 돌고 있는 일이 있는지 모듈마다 답한다 — consent 가 job·assistant·coverletter 를 직접 읽으면 모듈이 순환한다 */
public interface ActiveWorkPort {

    boolean isRunning(Long userId);
}
