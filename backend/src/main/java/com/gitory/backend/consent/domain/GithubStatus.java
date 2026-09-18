package com.gitory.backend.consent.domain;

import java.time.Instant;
import java.util.List;

public record GithubStatus(
        boolean connected,
        List<String> scopes,
        Instant connectedAt,
        Instant lastCollectedAt) {

    static GithubStatus from(GithubConnection connection) {
        return new GithubStatus(connection.isActive(), connection.scopeList(), connection.getGrantedAt(), null);
    }

    static GithubStatus notConnected() {
        return new GithubStatus(false, List.of(), null, null);
    }
}
