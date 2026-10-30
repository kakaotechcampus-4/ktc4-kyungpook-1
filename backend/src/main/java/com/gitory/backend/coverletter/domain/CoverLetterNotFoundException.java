package com.gitory.backend.coverletter.domain;

public class CoverLetterNotFoundException extends RuntimeException {

    public CoverLetterNotFoundException() {
        super("자소서나 문항을 찾을 수 없다");
    }
}
