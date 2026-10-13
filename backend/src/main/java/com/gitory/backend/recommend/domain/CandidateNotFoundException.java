package com.gitory.backend.recommend.domain;

public class CandidateNotFoundException extends RuntimeException {

    public CandidateNotFoundException() {
        super("이 저장소의 후보가 아니다");
    }
}
