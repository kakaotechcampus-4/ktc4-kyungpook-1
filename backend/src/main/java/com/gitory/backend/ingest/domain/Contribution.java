package com.gitory.backend.ingest.domain;

public record Contribution(int mine, int team, double ratio, ContributionLevel level) {

    static final double PARTIAL_BELOW = 0.2;
    static final double MAJOR_FROM = 0.5;
    static final int RECOMMEND_MIN_COMMITS = 10;

    public static Contribution of(int ownCommitCount, int commitCount) {

        double ratio = commitCount == 0 ? 0 : (double) ownCommitCount / commitCount;

        return new Contribution(ownCommitCount, commitCount - ownCommitCount, ratio, levelOf(ownCommitCount, ratio));

    }

    public boolean recommended() {

        return (level == ContributionLevel.SHARED || level == ContributionLevel.MAJOR) && mine >= RECOMMEND_MIN_COMMITS;

    }

    private static ContributionLevel levelOf(int ownCommitCount, double ratio) {

        if (ownCommitCount == 0) {
            return ContributionLevel.NONE;
        }
        if (ratio < PARTIAL_BELOW) {
            return ContributionLevel.PARTIAL;
        }
        if (ratio < MAJOR_FROM) {
            return ContributionLevel.SHARED;
        }

        return ContributionLevel.MAJOR;

    }
}
