package com.gitory.backend.job.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.job.domain.RepoRefreshService;
import com.gitory.backend.job.domain.RepositoryNotFoundException;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Repositories")
public class RepoUpdateController {

    private final RepoRefreshService refreshes;

    @PostMapping("/api/repo-updates/check")
    @Operation(summary = "새 활동 확인", description = """
            정리한 적이 있는 레포 가운데 마지막 정리 뒤에 push 가 있는 레포의 분석 Job 을 사용자 대신 접수합니다.
            GitHub 을 새로 부르지 않고 저장된 마지막 push 시각만 보므로, 화면은 레포 목록(GET /api/repos)을 먼저 읽어 시각을 새로 받아 둡니다.
            한 번에 push 가 최근인 레포부터 3개까지 접수하고, 분석 Job 이 진행 중이거나 마지막 확인으로 접수한 지 6시간이 안 된 레포는 건너뜁니다.
            쓸 수 있는 GitHub 연결이 없으면 오류가 아니라 queued 0 으로 답합니다.
            분석이 끝나면 새 일이 든 후보로 카드 초안까지 서버가 만들고, 초안이 끝나면 GET /api/repo-updates 에 나타납니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "접수한 레포 수"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<RepoUpdateCheckResponse> check(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(new RepoUpdateCheckResponse(refreshes.check(loginUser.id())));

    }

    @GetMapping("/api/repo-updates")
    @Operation(summary = "새 활동 알림 목록", description = """
            서버가 스스로 다시 읽어 카드 초안까지 만든 레포 가운데 사용자가 아직 보지 않은 것을 레포마다 하나로 묶어 최근에 끝난 순서로 돌려줍니다.
            cardCount 는 그 레포에서 보지 않은 알림의 카드 수 합이고, finishedAt 은 그중 가장 늦게 끝난 시각입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "알림 배열. 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<RepoUpdateResponse>> updates(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(refreshes.updates(loginUser.id()).stream().map(RepoUpdateResponse::from).toList());

    }

    @PostMapping("/api/repo-updates/{repoId}/seen")
    @Operation(summary = "새 활동 알림 확인", description = """
            그 레포의 보지 않은 알림을 모두 본 것으로 닫아 목록에 다시 나오지 않게 합니다. 이미 닫혔거나 알림이 없어도 오류가 아닙니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "닫았음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UUID 가 아니거나 없는 레포이거나 남의 레포",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<RepoUpdateSeenResponse> seen(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "레포 목록 응답의 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String repoId) {

        refreshes.markSeen(toUuid(repoId), loginUser.id());

        return ApiResponse.ok(new RepoUpdateSeenResponse(true));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new RepositoryNotFoundException();
        }

    }
}
