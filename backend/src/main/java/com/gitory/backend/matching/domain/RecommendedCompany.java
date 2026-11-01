package com.gitory.backend.matching.domain;

import java.util.List;

public record RecommendedCompany(String companyId, String companyName, Long postingId, int requiredMet,
                                 int preferredMet, List<String> techs, List<Long> metCriterionIds) {
}
