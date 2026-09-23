package com.gitory.backend.job.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.job.domain.JobNotFoundException;
import com.gitory.backend.job.domain.JobQueryService;
import com.gitory.backend.job.domain.JobResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class JobController {

    private final JobQueryService jobQuery;

    @GetMapping("/api/jobs/{id}")
    public ApiResponse<JobResponse> job(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable String id) {

        return ApiResponse.ok(jobQuery.load(toUuid(id), loginUser.id()));
    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new JobNotFoundException();
        }

    }

}
