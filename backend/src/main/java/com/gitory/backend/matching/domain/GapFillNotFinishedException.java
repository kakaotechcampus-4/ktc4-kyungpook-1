package com.gitory.backend.matching.domain;

public class GapFillNotFinishedException extends RuntimeException {

    public GapFillNotFinishedException() {
        super("끝난 채우기 결과가 없어 고른 조건만 다시 찾을 수 없다");
    }
}
