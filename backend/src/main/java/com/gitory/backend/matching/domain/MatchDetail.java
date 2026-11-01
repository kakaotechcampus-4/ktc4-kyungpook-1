package com.gitory.backend.matching.domain;

import java.util.List;

public record MatchDetail(MatchTarget target, String experienceQuote, List<MatchCondition> conditions) {
}
