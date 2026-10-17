package com.gitory.backend.recommend.domain;

/** 후보에 쓸 입력이 규칙에 맞지 않을 때 발생하는 에러 */
public class InvalidCandidateInputException extends RuntimeException {

    public InvalidCandidateInputException(String message) {
        super(message);
    }
}
