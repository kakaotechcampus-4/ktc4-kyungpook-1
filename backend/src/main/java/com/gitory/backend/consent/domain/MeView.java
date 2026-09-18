package com.gitory.backend.consent.domain;

import java.time.Instant;
import java.util.List;

public record MeView(
        String id,
        String login,
        String avatarUrl,
        Plan plan,
        Github github,
        Stats stats) {

    public enum Plan {
        FREE
    }

    public record Github(
            boolean connected,
            List<String> scopes,
            Instant connectedAt,
            Instant lastCollectedAt) {

        static Github notConnected() {
            return new Github(false, List.of(), null, null);
        }
    }

    public record Stats(
            int confirmedCards,
            int analyzedRepos,
            int interviewTurns,
            int remainingCandidates) {

        static Stats none() {
            return new Stats(0, 0, 0, 0);
        }
    }
}
