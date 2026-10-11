package com.gitory.backend.ingest.api;

import com.gitory.backend.ingest.domain.Contribution;
import com.gitory.backend.ingest.domain.ContributionLevel;

public record ContributionResponse(int mine, int team, double ratio, ContributionLevel level) {

    static ContributionResponse from(Contribution contribution) {
        return new ContributionResponse(contribution.mine(), contribution.team(), contribution.ratio(),
                contribution.level());
    }
}
