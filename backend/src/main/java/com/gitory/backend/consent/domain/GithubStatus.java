package com.gitory.backend.consent.domain;

import java.time.Instant;
import java.util.List;

public record GithubStatus(
        boolean connected,
        List<String> scopes,
        Instant connectedAt,
        Instant lastCollectedAt) {

    static GithubStatus notConnected() {
        return new GithubStatus(false, List.of(), null, null);
    }
}
