package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.MatchCount;

public record MatchCountResponse(int total, int met) {

    public static MatchCountResponse from(MatchCount count) {

        return new MatchCountResponse(count.total(), count.met());

    }
}
