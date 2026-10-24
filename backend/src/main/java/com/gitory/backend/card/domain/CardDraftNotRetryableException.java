package com.gitory.backend.card.domain;

/** 초안 실패가 없거나, 되묻기·확정으로 1번 버전을 지나 초안을 다시 담을 수 없는 카드에 다시 시도가 올 때 발생하는 에러 */
public class CardDraftNotRetryableException extends RuntimeException {

    public CardDraftNotRetryableException() {
        super("다시 초안을 만들 수 있는 카드가 아닙니다.");
    }
}
