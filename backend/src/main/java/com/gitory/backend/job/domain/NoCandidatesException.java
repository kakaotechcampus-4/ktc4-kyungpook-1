package com.gitory.backend.job.domain;

public class NoCandidatesException extends RuntimeException {

    public NoCandidatesException() {
        super("카드로 만들 후보를 하나 이상 골라야 한다");
    }
}
