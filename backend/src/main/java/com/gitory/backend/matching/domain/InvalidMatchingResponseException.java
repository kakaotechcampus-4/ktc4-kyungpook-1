package com.gitory.backend.matching.domain;

public class InvalidMatchingResponseException extends RuntimeException {

    public InvalidMatchingResponseException(Throwable cause) {
        super("AI 매칭 검색이 다시 해도 같은 실패이거나 이상한 응답을 돌려줬다", cause);
    }
}
