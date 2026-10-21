package com.gitory.backend.matching.domain;

import java.util.List;
import java.util.UUID;

public record MatchCondition(UUID id, CriterionType type, String text, String quote, List<MatchSupport> supports) {
}
