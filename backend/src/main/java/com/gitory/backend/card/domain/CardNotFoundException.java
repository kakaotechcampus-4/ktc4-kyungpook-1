package com.gitory.backend.card.domain;

/** 요청한 카드가 없거나 내 카드가 아닐 때 발생하는 에러 */
public class CardNotFoundException extends RuntimeException {

    public CardNotFoundException() {
        super("요청한 카드를 찾을 수 없습니다.");
    }
}
