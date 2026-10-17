package com.gitory.backend.consent.domain;

public record UserStats(
        int confirmedCards,
        int analyzedRepos,
        int interviewTurns,
        int remainingCandidates) {
}
