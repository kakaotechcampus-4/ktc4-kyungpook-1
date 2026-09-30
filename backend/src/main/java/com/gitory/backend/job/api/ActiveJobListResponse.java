package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.ActiveJobRow;

import java.util.ArrayList;
import java.util.List;

public record ActiveJobListResponse(List<ActiveJobResponse> jobs) {

    static ActiveJobListResponse from(List<ActiveJobRow> rows) {
        List<ActiveJobResponse> jobs = new ArrayList<>();
        for (ActiveJobRow row : rows) {
            jobs.add(ActiveJobResponse.from(row));
        }
        return new ActiveJobListResponse(jobs);
    }
}
