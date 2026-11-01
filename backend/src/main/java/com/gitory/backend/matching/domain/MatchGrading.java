package com.gitory.backend.matching.domain;

/** 필수 조건으로 등급을 세고, 필수가 없는 공고만 우대 조건으로 센다 */
public final class MatchGrading {

    private MatchGrading() {
    }

    public static MatchGrade fit(MatchCount required, MatchCount preferred) {

        MatchCount counted = required.total() > 0 ? required : preferred;
        if (counted.total() == 0) {
            throw new IllegalArgumentException("등급을 셀 필수·우대 조건이 없는 공고는 등급 대상이 아니다");
        }

        if (counted.met() == counted.total()) {
            return MatchGrade.A;
        }
        if (counted.met() * 2 >= counted.total()) {
            return MatchGrade.B;
        }
        return counted.met() >= 1 ? MatchGrade.C : MatchGrade.D;

    }
}
