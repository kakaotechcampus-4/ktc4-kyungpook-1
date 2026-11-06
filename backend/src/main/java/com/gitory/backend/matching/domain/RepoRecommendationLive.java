package com.gitory.backend.matching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepoRecommendationLive(int cardCount, List<String> techs, List<Candidate> candidates, boolean finished) {

    public record Candidate(String companyId, String companyName, String logoUrl, Posting posting, int overlap,
                            RepoRecommendationLiveState state, boolean failed, List<Hit> hits) {

        public static Candidate waiting(String companyId, String companyName, String logoUrl, Posting posting,
                                        int overlap) {

            return new Candidate(companyId, companyName, logoUrl, posting, overlap,
                    RepoRecommendationLiveState.WAITING, false, List.of());

        }
    }

    public record Posting(UUID id, String title, Instant deadline) {
    }

    public record Hit(CriterionType type, String text, String cardTitle) {
    }
}
