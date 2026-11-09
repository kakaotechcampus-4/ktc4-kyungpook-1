package com.gitory.backend.assistant.port;

public class RepoNotFoundException extends RuntimeException {

    public RepoNotFoundException() {
        super("정리를 시작할 레포를 찾을 수 없다");
    }
}
