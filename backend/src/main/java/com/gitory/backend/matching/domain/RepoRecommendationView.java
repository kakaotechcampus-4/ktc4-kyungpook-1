package com.gitory.backend.matching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepoRecommendationView(RepoRecommendationStatus status, Instant computedAt, List<Company> companies) {

    public record Company(String companyId, String companyName, String logoUrl, Posting posting, List<String> techs,
                          int requiredMet, int preferredMet) {
    }

    public record Posting(UUID id, String title, Instant deadline, boolean closed) {
    }
}
