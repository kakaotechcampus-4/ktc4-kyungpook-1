package com.gitory.backend.matching.infra;

import java.util.List;

public record RocketpunchProperties(String apiKey, List<String> skipCompanyIds) {

    public RocketpunchProperties {
        apiKey = apiKey == null ? "" : apiKey.strip();
        skipCompanyIds = skipCompanyIds == null ? List.of() : List.copyOf(skipCompanyIds);
    }
}
