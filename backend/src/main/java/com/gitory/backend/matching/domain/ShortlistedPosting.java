package com.gitory.backend.matching.domain;

import java.util.List;

public record ShortlistedPosting(JobPosting posting, List<JobPostingCriterion> criteria, TechOverlap overlap) {
}
