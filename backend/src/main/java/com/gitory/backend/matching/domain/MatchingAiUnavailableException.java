package com.gitory.backend.matching.domain;

public class MatchingAiUnavailableException extends RuntimeException {

    public MatchingAiUnavailableException(String message) {
        super(message);
    }

    public MatchingAiUnavailableException(Throwable cause) {
        super("AI 매칭 검색을 쓸 수 없다", cause);
    }
}
