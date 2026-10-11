package com.gitory.backend.card.domain;

/** 확정된 카드를 다시 열지 않고 고치려 할 때 발생하는 에러 */
public class CardConfirmedException extends RuntimeException {

    public CardConfirmedException() {
        super("확정된 카드는 다시 열어야 수정할 수 있습니다.");
    }
}
