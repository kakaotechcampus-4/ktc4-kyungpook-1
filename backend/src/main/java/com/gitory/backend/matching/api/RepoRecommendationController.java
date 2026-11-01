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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Matches")
public class RepoRecommendationController {

    private final RepoRecommendationQueryService recommendations;

    @GetMapping("/api/repos/{repoId}/recommendations")
    @Operation(summary = "레포 추천 기업 조회",
            description = "그 레포의 확정 카드로 계산해 저장해 둔 추천 기업을 돌려주며, 열 때 계산하지 않습니다. 카드 초안이 끝나거나 확정 카드가 바뀌고 1분이 지나면 서버가 다시 계산하고, 그동안 status 는 COMPUTING 입니다. 기업마다 이 레포 카드가 채웠다고 AI 가 판단한 조건 수와 그 기술 이름을 담습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 상태와 기업. 계산한 적이 없으면 NONE 과 빈 배열"),
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

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw new RecommendationRepositoryNotFoundException();
        }

    }
}
