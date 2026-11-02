package com.gitory.backend.matching.domain;

import java.util.List;

public record MatchDetail(MatchTarget target, String experienceQuote, MatchConditionStatus experienceStatus,
                          boolean needsJudgement, MatchSummary summary, List<MatchCondition> conditions) {
}
