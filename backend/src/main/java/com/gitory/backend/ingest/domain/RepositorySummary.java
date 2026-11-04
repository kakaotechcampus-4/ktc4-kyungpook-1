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
        int cardCount,
        String userDescription,
        String githubDescription,
        String readmeSummary) {

    public RepositorySummary(Long connectionId, UUID publicId, String ownerLogin, String name, String language,
                             Instant activeFrom, Instant activeTo, Instant lastAnalyzedAt, Instant countedAt,
                             int commitCount, int ownCommitCount, int prCount, int ownPrCount, int reviewedPrCount,
                             String userDescription, String githubDescription, String readmeSummary) {
        this(connectionId, publicId, ownerLogin, name, language, activeFrom, activeTo, lastAnalyzedAt, countedAt,
                commitCount, ownCommitCount, prCount, ownPrCount, reviewedPrCount, null, 0,
                userDescription, githubDescription, readmeSummary);
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
                shownCandidates, cards, userDescription, githubDescription, readmeSummary);

    }

    public DescriptionSource descriptionSource() {

        if (isFilled(userDescription)) {
            return DescriptionSource.USER;
        }
        if (isFilled(githubDescription)) {
            return DescriptionSource.GITHUB;
        }

        return isFilled(readmeSummary) ? DescriptionSource.README : null;

    }

    /** 사용자가 쓴 설명, GitHub 설명, README 문장 순으로 처음 있는 것이고 셋 다 없으면 null 이다 */
    public String description() {

        DescriptionSource source = descriptionSource();
        if (source == null) {
            return null;
        }

        return switch (source) {
            case USER -> userDescription;
            case GITHUB -> githubDescription;
            case README -> readmeSummary;
        };

    }

    private static boolean isFilled(String text) {

        return text != null && !text.isBlank();

    }
}
