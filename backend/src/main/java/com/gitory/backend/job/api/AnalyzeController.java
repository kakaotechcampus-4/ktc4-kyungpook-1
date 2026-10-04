package com.gitory.backend.job.api;


import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.job.domain.JobIntakeResult;
import com.gitory.backend.job.domain.JobIntakeService;
import com.gitory.backend.job.domain.RepositoryNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Repositories", description = "선택한 저장소의 분석 Job 접수")
public class AnalyzeController {

    private static final int POLL_AFTER_MS = 2000;

    private final RepositoryOwnershipService ownership;
    private final JobIntakeService jobIntake;

    @PostMapping("/api/repos/{id}/analyze")
    @Operation(summary = "저장소 분석 요청 접수", description = """
            본인 소유의 userRepositoryId로 분석 Job을 접수합니다. 같은 요청 키 또는 실행 중인
            같은 저장소 Job이 있으면 기존 jobId를 돌려줍니다. Idempotency-Key 생략 시 서버가 생성합니다.
            응답은 완료 결과가 아닌 접수 결과이며, 이후 GET /api/jobs/{id}로 상태를 조회합니다.
            현재 실행 워커는 미구현입니다. 변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새 Job 또는 기존 Job 접수 결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 소유 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 요청 키가 다른 저장소 분석에 사용됨",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class), examples = @ExampleObject(
                            value = "{\"data\":null,\"error\":{\"code\":\"IDEMPOTENCY_KEY_MISMATCH\",\"message\":\"이미 다른 레포 분석에 쓰인 요청 키입니다.\"}}")))
    })
    public ApiResponse<StartedResponse> analyze(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "본인이 선택한 저장소의 공개 UUID (GitHub repository ID가 아님)",
                    schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "재시도 시 동일하게 보내는 요청 키. 다른 저장소에 재사용하면 409")
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
