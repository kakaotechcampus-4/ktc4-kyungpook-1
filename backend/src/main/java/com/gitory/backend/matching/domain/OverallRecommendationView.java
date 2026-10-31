package com.gitory.backend.matching.domain;

import java.time.Instant;
import java.util.List;

public record OverallRecommendationView(OverallRecommendationStatus status, Instant computedAt, boolean stale,
                                        Progress progress, List<Company> companies) {

    public record Progress(int done, int total) {
    }

    public record Company(String companyId, String companyName, String logoUrl, RepoRecommendationView.Posting posting,
                          int requiredMet, int requiredTotal, int preferredMet, int preferredTotal) {
    }
}
