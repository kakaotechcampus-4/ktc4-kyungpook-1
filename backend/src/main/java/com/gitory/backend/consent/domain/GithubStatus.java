package com.gitory.backend.consent.domain;

import java.time.Instant;
import java.util.List;

public record GithubStatus(
        boolean connected,
        List<String> scopes,
        Instant connectedAt,
        Instant lastCollectedAt) {

    static GithubStatus from(GithubConnection connection, Instant lastCollectedAt) {

        return new GithubStatus(connection.isActive(), connection.scopeList(), connection.getGrantedAt(), lastCollectedAt);

    }

    static GithubStatus notConnected(Instant lastCollectedAt) {

        return new GithubStatus(false, List.of(), null, lastCollectedAt);

    }
}
