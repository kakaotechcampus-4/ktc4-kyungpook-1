package com.gitory.backend.ingest.api;

import com.gitory.backend.ingest.domain.ContributionLevel;

public record ContributionResponse(int mine, int team, double ratio, ContributionLevel level) {
}
