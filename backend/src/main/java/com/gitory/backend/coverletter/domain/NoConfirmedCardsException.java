package com.gitory.backend.coverletter.domain;

public class NoConfirmedCardsException extends RuntimeException {

    public NoConfirmedCardsException() {
        super("확정한 카드가 없어 자소서를 만들 수 없다");
    }
}
