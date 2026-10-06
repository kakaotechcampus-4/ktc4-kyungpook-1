package com.gitory.backend.job.api;

import com.gitory.backend.job.domain.ActiveJobRow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

public record ActiveJobListResponse(
        @Schema(description = "활성 Job 목록. 없으면 빈 배열") List<ActiveJobResponse> jobs) {

    static ActiveJobListResponse from(List<ActiveJobRow> rows) {
        List<ActiveJobResponse> jobs = new ArrayList<>();
        for (ActiveJobRow row : rows) {
            jobs.add(ActiveJobResponse.from(row));
        }
        return new ActiveJobListResponse(jobs);
    }
}
