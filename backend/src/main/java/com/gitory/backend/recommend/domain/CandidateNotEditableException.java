package com.gitory.backend.recommend.domain;

public class CandidateNotEditableException extends RuntimeException {

    public CandidateNotEditableException() {
        super("이미 카드로 쓴 후보는 고칠 수 없다");
    }
}
