package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingCriterion;

public record PostingCondition(JobPosting posting, JobPostingCriterion criterion) {
}
