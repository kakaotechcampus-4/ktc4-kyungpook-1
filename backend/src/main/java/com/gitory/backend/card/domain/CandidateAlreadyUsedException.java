package com.gitory.backend.card.domain;

public class CandidateAlreadyUsedException extends RuntimeException {

    public CandidateAlreadyUsedException() {
        super("이미 카드가 만들어진 후보다");
    }
}
