package com.gitory.backend.ingest.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
import com.gitory.backend.ingest.domain.RepositoryDetailService;
import com.gitory.backend.ingest.domain.RepositoryListService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Repos", description = "본인의 GitHub 저장소 목록·상세 조회")
public class RepoController {

    private final RepositoryListService repositoryList;
    private final RepositoryDetailService repositoryDetail;

    @GetMapping("/api/repos")
    @Operation(summary = "저장소 목록 조회", description = "부를 때마다 GitHub 에서 저장소 목록을 다시 받아 반영한 뒤, 이번에 GitHub 이 돌려준 저장소만 돌려줍니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장소 배열. 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "GITHUB_UNAUTHORIZED: 쓸 수 있는 GitHub 연결이 없거나 GitHub 이 토큰을 거절함",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "그 밖의 GitHub 호출 실패",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<RepoSummaryResponse>> repos(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(repositoryList.list(loginUser.id()).stream().map(RepoSummaryResponse::from).toList());

    }

    @GetMapping("/api/repos/{id}")
    @Operation(summary = "저장소 상세 조회", description = "목록 한 줄과 같은 칸에 분석 전 안내(읽는 것·안 읽는 것·예상 시간)를 더해 돌려줍니다. GitHub 을 부르지 않고 저장된 값만 읽습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장소 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<RepoDetailResponse> repo(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 저장소 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(RepoDetailResponse.from(repositoryDetail.detail(toUuid(id), loginUser.id())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ConnectedRepositoryNotFoundException();
        }

    }
}
