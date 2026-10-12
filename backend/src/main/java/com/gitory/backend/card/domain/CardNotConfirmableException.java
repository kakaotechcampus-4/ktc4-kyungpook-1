package com.gitory.backend.card.domain;

/** 확정에 필요한 칸이 비어 있을 때 발생하는 에러 */
public class CardNotConfirmableException extends RuntimeException {

    public CardNotConfirmableException() {
        super("상황과 행동을 채워야 확정할 수 있습니다.");
    }
}
