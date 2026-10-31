package com.gitory.backend.matching.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.matching.domain.OverallRecommendationQueryService;
import com.gitory.backend.matching.domain.OverallRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Matches")
public class OverallRecommendationController {

    private final OverallRecommendationService recommendations;
    private final OverallRecommendationQueryService queries;

    @GetMapping("/api/recommendations")
    @Operation(summary = "전체 추천 기업 조회",
            description = "'내 조건으로 매칭하기' 로 계산해 저장해 둔 기업을 돌려주며, 열 때 계산하지 않습니다. 계산 중이면 progress 로 확인한 공고 수를 담고, 계산 뒤 확정 카드나 인적사항이 바뀌었으면 stale 이 true 입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 상태와 기업. 계산한 적이 없으면 NONE 과 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<OverallRecommendationResponse> overallRecommendations(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(OverallRecommendationResponse.from(queries.of(loginUser.id())));

    }

    @PostMapping("/api/recommendations")
    @Operation(summary = "전체 추천 계산 시작",
            description = "모든 확정 카드로 맞는 기업을 배경에서 계산하기 시작하고 조회와 같은 응답을 돌려줍니다. 이미 계산 중이면 새로 시작하지 않습니다. 계산은 1분 안팎이며 화면은 조회 API 로 진행을 읽습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "시작한 뒤의 추천 상태(보통 COMPUTING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<OverallRecommendationResponse> startOverallRecommendation(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        recommendations.start(loginUser.id());
        return ApiResponse.ok(OverallRecommendationResponse.from(queries.of(loginUser.id())));

    }
}
