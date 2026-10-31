package com.gitory.backend.matching.domain;

public record OverallCompany(String companyId, String companyName, Long postingId, int requiredMet, int requiredTotal,
                             int preferredMet, int preferredTotal) {
}
