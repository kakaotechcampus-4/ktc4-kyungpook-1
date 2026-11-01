package com.gitory.backend.coverletter.domain;

public class CoverLetterPostingNotFoundException extends RuntimeException {

    public CoverLetterPostingNotFoundException() {
        super("자소서를 시작할 공고를 찾을 수 없다");
    }
}
