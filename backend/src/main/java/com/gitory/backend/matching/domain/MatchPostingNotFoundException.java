package com.gitory.backend.matching.domain;

public class MatchPostingNotFoundException extends RuntimeException {

    public MatchPostingNotFoundException() {
        super("매칭 대상 공고를 찾을 수 없다");
    }
}
