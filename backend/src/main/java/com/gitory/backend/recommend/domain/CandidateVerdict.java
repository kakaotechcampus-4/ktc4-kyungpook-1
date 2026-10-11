package com.gitory.backend.recommend.domain;

public enum CandidateVerdict {
    OK,
    EMPTY,
    PARTIAL;

    public static CandidateVerdict of(int candidateCount, boolean partial) {

        if (candidateCount == 0) {
            return EMPTY;
        }

        return partial ? PARTIAL : OK;

    }
}
