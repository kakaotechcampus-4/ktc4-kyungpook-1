package com.gitory.backend.consent.port;

import java.time.Instant;

public record MeStats(
        int confirmedCards,
        int analyzedRepos,
        int interviewTurns,
        int remainingCandidates,
        Instant lastCollectedAt) {
}
