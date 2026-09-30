package com.gitory.backend.job.api;


import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.job.domain.JobIntakeResult;
import com.gitory.backend.job.domain.JobIntakeService;
import com.gitory.backend.job.domain.RepositoryNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AnalyzeController {

    private static final int POLL_AFTER_MS = 2000;

    private final RepositoryOwnershipService ownership;
    private final JobIntakeService jobIntake;

    @PostMapping("/api/repos/{id}/analyze")
    public ApiResponse<StartedResponse> analyze(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userRepositoryId = ownership.findOwnedRepository(toUuid(id), loginUser.id())
                .orElseThrow(RepositoryNotFoundException::new);

        JobIntakeResult result = jobIntake.intake(loginUser.id(), userRepositoryId, idempotencyKey);

        return ApiResponse.ok(new StartedResponse(result.jobId().toString(), result.state(), POLL_AFTER_MS));
    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new RepositoryNotFoundException();
        }

    }

}
