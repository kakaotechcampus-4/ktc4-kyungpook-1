package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.JobPosting;

public record MatchableCondition(JobPosting posting, Long criterionId, CriterionType type) {
}
