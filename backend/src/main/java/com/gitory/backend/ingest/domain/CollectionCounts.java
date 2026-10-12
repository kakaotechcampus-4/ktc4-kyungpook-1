package com.gitory.backend.ingest.domain;

import java.time.Instant;

public record CollectionCounts(
        Instant countedAt,
        int commitCount,
        int ownCommitCount,
        int prCount,
        int ownPrCount,
        int reviewedPrCount) {

    public Contribution contribution() {

        return Contribution.of(ownCommitCount, commitCount);

    }
}
