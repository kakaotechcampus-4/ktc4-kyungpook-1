package com.gitory.backend.profile.domain;

/** 점수·등급은 시험마다 모양이 달라(850, IH, N2) 글로 두고, 시험 이름은 OTHER 일 때만 있다 */
public record LanguageScore(LanguageTest test, String testName, String score, String acquiredMonth) {
}
