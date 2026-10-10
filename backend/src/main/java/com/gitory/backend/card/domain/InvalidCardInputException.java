package com.gitory.backend.card.domain;

/** 카드 입력이 규칙에 맞지 않을 때 발생하는 에러 */
public class InvalidCardInputException extends RuntimeException {

    public InvalidCardInputException(String message) {
        super(message);
    }
}
