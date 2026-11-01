package com.gitory.backend.matching.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.matching.domain.MatchDetailService;
import com.gitory.backend.matching.domain.MatchListService;
import com.gitory.backend.matching.domain.MatchPostingNotFoundException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Matches")
public class MatchController {

    private final MatchListService matches;
    private final MatchDetailService details;

    @GetMapping("/api/matches")
    @Operation(summary = "기업·직무 매칭 목록 조회",
            description = "AI 를 부르지 않고 공고와 조건 수만 돌려줍니다. 모든 공고의 fit 은 null, required·preferred 의 met 은 0 이고 total 은 조건 수입니다. 마감이 7일 안에 남은 공고를 마감순으로 앞에 두고, 나머지는 회사마다 돌아가며 놓으며 마감일이 없는 공고는 뒤입니다. 근거와 등급은 상세에서만 셉니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공고 배열. 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<MatchTargetResponse>> matches(
            @Parameter(description = "주면 그 회사(companyId)의 공고만 돌려준다. 없는 회사면 빈 배열")
            @RequestParam(required = false) String companyId) {

        return ApiResponse.ok(matches.list(companyId).stream().map(MatchTargetResponse::from).toList());

    }

    @GetMapping("/api/matches/{id}")
    @Operation(summary = "기업·직무 매칭 상세 조회",
            description = "열 때 AI 검색으로 이 공고의 조건마다 근거가 되는 확정 카드의 행동(A)·결과(R) 문장을 거리가 가까운 순으로 돌려줍니다. 근거가 없는 조건도 빈 배열로 포함하고, 확정 카드가 없으면 fit 은 null 이고 근거는 모두 빈 배열입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공고 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UUID 가 아니거나 없는 공고이거나 목록 대상이 아닌 공고(마감·사라짐·색인 안 됨·등급 조건 없음)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "AI 검색이 다시 해도 같은 실패이거나 응답이 계약과 다름",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI 검색이 잠시 실패해 근거를 찾지 못함. 다시 시도할 수 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<MatchDetailResponse> match(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 공고 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(MatchDetailResponse.from(details.detail(toUuid(id), loginUser.id())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new MatchPostingNotFoundException();
        }

    }
}
