package com.gitory.backend.job.domain;

public class TooManyCandidatesException extends RuntimeException {

    private final int max;

    public TooManyCandidatesException(int max) {
        super("카드는 한 번에 " + max + "개까지 만들 수 있다");
        this.max = max;
    }

    public int max() {

        return max;

    }
}
