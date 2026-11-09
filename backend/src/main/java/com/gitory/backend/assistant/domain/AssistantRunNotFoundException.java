package com.gitory.backend.assistant.domain;

public class AssistantRunNotFoundException extends RuntimeException {

    public AssistantRunNotFoundException() {
        super("홈 한 줄 명령 실행을 찾을 수 없다");
    }
}
