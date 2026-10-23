package com.gitory.backend.agent.port;

import java.util.List;

public record MatchingSearchResult(List<CriterionMatch> matches, int collectionCriteria) {
}
