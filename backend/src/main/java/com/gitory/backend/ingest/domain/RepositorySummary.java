package com.gitory.backend.ingest.domain;

import java.time.Instant;
import java.util.UUID;

public record RepositorySummary(
        Long connectionId,
        UUID publicId,
        String ownerLogin,
        String name,
        String language,
        Instant activeFrom,
        Instant activeTo,
        Instant lastAnalyzedAt,
        Instant countedAt,
        int commitCount,
        int ownCommitCount,
        int prCount,
        int ownPrCount,
        int reviewedPrCount,
        Integer candidateCount,
        int cardCount) {

    public RepositorySummary(Long connectionId, UUID publicId, String ownerLogin, String name, String language,
                             Instant activeFrom, Instant activeTo, Instant lastAnalyzedAt, Instant countedAt,
                             int commitCount, int ownCommitCount, int prCount, int ownPrCount, int reviewedPrCount) {
        this(connectionId, publicId, ownerLogin, name, language, activeFrom, activeTo, lastAnalyzedAt, countedAt,
                commitCount, ownCommitCount, prCount, ownPrCount, reviewedPrCount, null, 0);
    }

    public Contribution contribution() {

        return Contribution.of(ownCommitCount, commitCount);

    }

    public RepositorySummary withCounts(int candidates, Instant groupedAt, int cards) {

        boolean groupedSinceAnalysis = lastAnalyzedAt != null && groupedAt != null
                && !groupedAt.isBefore(lastAnalyzedAt);
        Integer shownCandidates = candidates > 0 || groupedSinceAnalysis ? candidates : null;

        return new RepositorySummary(connectionId, publicId, ownerLogin, name, language, activeFrom, activeTo,
                lastAnalyzedAt, countedAt, commitCount, ownCommitCount, prCount, ownPrCount, reviewedPrCount,
                shownCandidates, cards);

    }
}
