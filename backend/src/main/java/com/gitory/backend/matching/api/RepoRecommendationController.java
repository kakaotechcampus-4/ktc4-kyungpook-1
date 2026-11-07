package com.gitory.backend.matching.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.matching.domain.RecommendationRepositoryNotFoundException;
import com.gitory.backend.matching.domain.RepoRecommendationQueryService;
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
@Tag(name = "Matches")
public class RepoRecommendationController {

    private final RepoRecommendationQueryService recommendations;

    @GetMapping("/api/repos/{repoId}/recommendations")
    @Operation(summary = "레포 추천 기업 조회",
            description = "그 레포의 카드(작성 중과 확정)로 계산해 저장해 둔 추천 기업을 돌려주며, 열 때 계산하지 않습니다. 계산한 적이 없는데 카드가 있으면 조회할 때 계산을 예약합니다(여는 요청 안에서 계산하지는 않습니다). 카드 초안이 끝나거나(서버가 스스로 다시 읽어 만든 초안은 제외) 확정 카드가 바뀌거나 카드를 지우고 1분이 지나면 서버가 다시 계산하고, 그동안 status 는 COMPUTING 이며 queued 는 계산이 시작되기 전까지 true 입니다. 계산을 시작해 후보를 정하는 동안은 live 가 null 이고, 이어서 후보 회사를 하나씩 확인하는 과정이 live 에 담기며, 계산이 끝난 뒤에도 서버가 다시 뜨기 전까지 남습니다. 기업마다 이 레포 카드가 채웠다고 AI 가 판단한 조건 수와 그 기술 이름을 담습니다. fresh 는 바로 앞 계산 결과에 없던 기업이면 true 입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 상태와 기업, 다시 계산 예약 여부(queued), 고르는 과정(live). 계산한 적도 카드도 없으면 NONE 과 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UUID 가 아니거나 없는 레포이거나 남의 레포",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<RepoRecommendationResponse> recommendations(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "레포 목록 응답의 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String repoId) {

        return ApiResponse.ok(RepoRecommendationResponse.from(recommendations.of(toUuid(repoId), loginUser.id())));

    }

    @PostMapping("/api/repos/{repoId}/recommendations/refresh")
    @Operation(summary = "레포 추천 기업 다시 고르기", description = "그 레포의 추천 기업을 다음 배경 작업 차례에 바로 다시 계산하도록 예약하고, 조회와 같은 응답을 돌려줍니다. 이때 queued 는 true 이고 계산이 끝나면 조회에서 바꾼 결과를 읽습니다. 계산이 끝나 결과를 바꿀 때 바뀌기 전 기업을 남겨 두어, 그 뒤 조회에서 바뀌기 전 결과에 없던 기업은 fresh 가 true 입니다. 변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회와 같은 모양의 추천 상태. queued 는 true"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UUID 가 아니거나 없는 레포이거나 남의 레포",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<RepoRecommendationResponse> refresh(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "레포 목록 응답의 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String repoId) {

        return ApiResponse.ok(RepoRecommendationResponse.from(recommendations.refresh(toUuid(repoId), loginUser.id())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw new RecommendationRepositoryNotFoundException();
        }

    }
}
