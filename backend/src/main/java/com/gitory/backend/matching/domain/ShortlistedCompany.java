package com.gitory.backend.matching.domain;

public record ShortlistedCompany(String companyId, String companyName, JobPosting representative,
                                 TechOverlap overlap, int postingsWithRequiredTech) {
}
