package com.gitory.backend.card.domain;

/** 초안을 만드는 Job 이 도는 카드에 되묻기 질문이나 답이 올 때 발생하는 에러 */
public class CardGeneratingException extends RuntimeException {

    public CardGeneratingException() {
        super("초안을 만드는 중인 카드입니다.");
    }
}
