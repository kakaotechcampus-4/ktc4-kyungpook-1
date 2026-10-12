package com.gitory.backend.recommend.api;

import com.gitory.backend.recommend.domain.ReadCoverage;

public record ReadCoverageResponse(int commitsRead, int commitsTotal, int prsRead, int prsTotal) {

    static ReadCoverageResponse from(ReadCoverage coverage) {

        if (coverage == null) {
            return null;
        }

        return new ReadCoverageResponse(coverage.commitsRead(), coverage.commitsTotal(), coverage.prsRead(),
                coverage.prsTotal());

    }
}
