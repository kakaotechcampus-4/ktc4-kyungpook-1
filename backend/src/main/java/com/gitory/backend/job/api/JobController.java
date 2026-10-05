package com.gitory.backend.job.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.job.domain.JobCancelService;
import com.gitory.backend.job.domain.JobNotFoundException;
import com.gitory.backend.job.domain.JobQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Jobs", description = "본인의 분석 Job 상태와 활성 작업 조회")
public class JobController {

    private final JobQueryService jobQuery;
    private final JobCancelService jobCancel;

    @GetMapping("/api/jobs/{id}")
    @Operation(summary = "Job 상세 조회", description = "본인 Job만 조회할 수 있습니다. 종료 상태이면 pollAfterMs=0이며 폴링을 멈춥니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Job 상태·단계·결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 Job 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<JobResponse> job(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "분석 접수 응답의 jobId", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(JobResponse.from(jobQuery.load(toUuid(id), loginUser.id())));
    }

    @GetMapping("/api/jobs")
    @Operation(summary = "활성 Job 목록 조회", description = "본인의 QUEUED·RUNNING Job을 반환합니다. 종료된 Job은 목록에서 제외됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "활성 Job 배열. 없으면 jobs=[]"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<ActiveJobListResponse> activeJobs(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(ActiveJobListResponse.from(jobQuery.loadActive(loginUser.id())));
    }

    @PostMapping("/api/jobs/{id}/cancel")
    @Operation(summary = "Job 취소", description = "본인의 QUEUED·RUNNING Job을 CANCELED로 바꿉니다. 이미 끝난 Job이면 바꾸지 않고 현재 상태를 돌려줍니다. 변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취소된 Job 또는 이미 끝난 Job의 현재 상태"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 Job 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<JobResponse> cancel(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "취소할 Job 의 jobId", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(JobResponse.from(jobCancel.cancel(toUuid(id), loginUser.id())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new JobNotFoundException();
        }

    }

}
