package com.gitory.backend.consent.domain;

import java.time.Instant;
import java.util.List;

public record GithubStatus(
        boolean connected,
        List<String> scopes,
        Instant connectedAt,
        Instant lastCollectedAt) {

    static GithubStatus from(GithubConnection connection, Instant lastCollectedAt) {

        boolean usable = connection.isActive()
                && (connection.getTokenExpiresAt() == null || connection.getTokenExpiresAt().isAfter(Instant.now()));
        return new GithubStatus(usable, connection.scopeList(), connection.getGrantedAt(), lastCollectedAt);

    }

    static GithubStatus notConnected(Instant lastCollectedAt) {

        return new GithubStatus(false, List.of(), null, lastCollectedAt);

    }
}
