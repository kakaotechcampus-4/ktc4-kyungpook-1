package com.gitory.backend.ingest.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
import com.gitory.backend.ingest.domain.RepositoryDetailService;
import com.gitory.backend.ingest.domain.RepositoryListService;
import com.gitory.backend.ingest.domain.RepositoryMaterialService;
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
    private final RepositoryMaterialService repositoryMaterial;

    @GetMapping("/api/repos")
    @Operation(summary = "저장소 목록 조회", description = "부를 때마다 GitHub 에서 접근할 수 있는 저장소와 최근에 커밋한 남의 공개 저장소(조직 소유 포함)를 다시 받아 반영한 뒤, 이번에 받은 저장소만 돌려줍니다. 커밋한 저장소를 찾지 못하면 접근할 수 있는 저장소만 돌려줍니다. 쓸 수 있는 연결이 없거나 GitHub 이 토큰·한도로 거절하면 저장된 저장소를 돌려줍니다. 어느 경우든 커밋이 없다고 센 빈 저장소와 내 프로필 README 저장소(아이디/아이디)는 빠집니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장소 배열. 없으면 빈 배열. 쓸 수 있는 GitHub 연결이 없거나 GitHub 이 토큰·한도로 거절하면 저장된 저장소를 돌려줌. 어느 경우든 커밋이 없다고 센 빈 저장소와 내 프로필 README 저장소(아이디/아이디)는 빠짐"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "그 밖의 GitHub 호출 실패",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<RepoSummaryResponse>> repos(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(repositoryList.list(loginUser.id(), loginUser.login()).stream().map(RepoSummaryResponse::from).toList());

    }

    @GetMapping("/api/repos/saved")
    @Operation(summary = "저장된 저장소 목록 조회", description = "GitHub 을 부르지 않고 마지막으로 받아 둔 저장소를 바로 돌려줍니다. 화면이 최신 목록(GET /api/repos)을 기다리는 동안 먼저 보여 줄 때 씁니다. 그사이 GitHub 에서 지웠거나 권한을 잃은 저장소도 남아 있을 수 있습니다. 커밋이 없다고 센 빈 저장소와 내 프로필 README 저장소(아이디/아이디)는 빠집니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장소 배열. 아직 GitHub 에서 목록을 받은 적이 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<RepoSummaryResponse>> savedRepos(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(repositoryList.saved(loginUser.id(), loginUser.login()).stream().map(RepoSummaryResponse::from).toList());

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

    @GetMapping("/api/repos/{id}/materials")
    @Operation(summary = "분석에 쓴 커밋·PR 목록 조회", description = "분석이 끝난 뒤 '읽은 커밋·PR 전체 보기' 에 씁니다. 이 저장소에 저장된 내 커밋을 분석에 쓴 것과 뺀 것(뺀 이유)을 함께 최근 순으로, 분석이 그 커밋을 묶은 PR 을 번호가 큰 순으로 한 번에 돌려줍니다. 커밋을 PR 에 묶는 규칙은 분석과 같습니다. GitHub 을 부르지 않고 저장된 값만 읽습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "커밋·PR 목록. 분석 전이면 둘 다 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<RepoMaterialsResponse> materials(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 저장소 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(RepoMaterialsResponse.from(
                repositoryMaterial.materials(toUuid(id), loginUser.id(), loginUser.login())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ConnectedRepositoryNotFoundException();
        }

    }
}
