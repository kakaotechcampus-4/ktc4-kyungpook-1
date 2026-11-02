package com.gitory.backend.matching.domain;

public class GapFillNotSkippedException extends RuntimeException {

    public GapFillNotSkippedException() {
        super("찾지 않기로 멈춘 조건이 아닌 조건을 다시 찾게 했다");
    }
}
