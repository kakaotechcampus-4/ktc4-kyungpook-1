package com.gitory.backend.matching.domain;

import java.util.List;

public record OverallCompany(String companyId, String companyName, Long postingId, int requiredMet, int requiredTotal,
                             int preferredMet, int preferredTotal, List<Condition> met, List<Condition> missing) {

    // met·missing 은 나중에 더한 칸이라 그 전에 저장한 JSON 에는 없어 null 로 읽히며, 그때는 빈 목록이다(다시 맞춰 보면 채워진다)
    public OverallCompany {
        met = met == null ? List.of() : List.copyOf(met);
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    public record Condition(String text, List<String> techs) {

        public Condition {
            techs = techs == null ? List.of() : List.copyOf(techs);
        }
    }
}
