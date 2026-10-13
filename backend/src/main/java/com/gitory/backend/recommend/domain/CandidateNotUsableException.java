package com.gitory.backend.recommend.domain;

public class CandidateNotUsableException extends RuntimeException {

    public CandidateNotUsableException() {
        super("이미 카드로 썼거나 제외한 후보가 섞여 있다");
    }
}
