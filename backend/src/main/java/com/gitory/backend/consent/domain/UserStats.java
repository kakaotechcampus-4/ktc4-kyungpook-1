package com.gitory.backend.consent.domain;

public record UserStats(
        int confirmedCards,
        int analyzedRepos,
        int interviewTurns,
        int remainingCandidates) {

    static UserStats none() {
        return new UserStats(0, 0, 0, 0);
    }
}
