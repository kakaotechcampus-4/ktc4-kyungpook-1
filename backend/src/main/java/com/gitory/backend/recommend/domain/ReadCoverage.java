package com.gitory.backend.recommend.domain;

import com.gitory.backend.ingest.domain.CollectionCounts;
import com.gitory.backend.ingest.domain.CollectionRunSummary;

public record ReadCoverage(int commitsRead, int commitsTotal, int prsRead, int prsTotal) {

    public static ReadCoverage of(CollectionRunSummary run, CollectionCounts counts, int storedCommits) {

        if (counts.countedAt() == null) {
            return null;
        }

        return new ReadCoverage(storedCommits, Math.max(counts.commitCount(), storedCommits),
                run.prsCollected(), Math.max(counts.prCount(), run.prsCollected()));

    }
}
