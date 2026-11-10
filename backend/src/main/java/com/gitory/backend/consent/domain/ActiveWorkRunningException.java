package com.gitory.backend.consent.domain;

public class ActiveWorkRunningException extends RuntimeException {

    public ActiveWorkRunningException() {
        super("진행 중인 작업이 있어 탈퇴할 수 없다");
    }
}
