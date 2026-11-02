package com.gitory.backend.matching.domain;

public class GapFillNeedsJudgementException extends RuntimeException {

    public GapFillNeedsJudgementException() {
        super("아직 맞춰 보지 않은 조건이 있어 모자란 조건을 채울 수 없다");
    }
}
