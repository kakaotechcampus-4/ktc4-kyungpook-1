package com.gitory.backend.matching.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.matching.domain.GapFillService;
import com.gitory.backend.matching.domain.MatchDetail;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final GapFillService gapFills;

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
            description = "열 때 AI 를 부르지 않고, 레포·전체 추천과 같이 저장해 둔 (카드, 조건) AI 판단으로 조건마다 status·metBy 를 붙입니다. 학력·경력은 인적사항과 견줍니다. 아직 판단하지 않은 조건이 있으면 needsJudgement 가 true 이고 그 조건은 UNJUDGED 입니다. fit 은 늘 null 입니다. gapFill 은 모자란 조건 채우기의 진행·결과이고, 찾는 중이면 pollAfterMs 마다 다시 읽습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공고 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UUID 가 아니거나 없는 공고이거나 목록 대상이 아닌 공고(마감·사라짐·필수·우대 조건 없음)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<MatchDetailResponse> match(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 공고 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        UUID postingId = toUuid(id);
        return ApiResponse.ok(withGapFill(details.detail(postingId, loginUser.id()), postingId, loginUser.id()));

    }

    @PostMapping("/api/matches/{id}/judgement")
    @Operation(summary = "공고 하나를 지금 매칭",
            description = "이 공고의 기술·경험 조건을 지금 확정 카드로 AI 에게 묻고 상세와 같은 응답을 돌려줍니다. 이미 판단한 (카드, 조건) 짝은 다시 묻지 않으며, 물을 짝이 남았을 때만 호출 한도를 셉니다. 최대 90초쯤 걸립니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "판단을 붙인 공고 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UUID 가 아니거나 없는 공고이거나 목록 대상이 아닌 공고",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "호출 한도를 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI 가 잠시 답하지 못함. 다시 시도할 수 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<MatchDetailResponse> judge(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 공고 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        UUID postingId = toUuid(id);
        return ApiResponse.ok(withGapFill(details.judge(postingId, loginUser.id()), postingId, loginUser.id()));

    }

    @PostMapping("/api/matches/{id}/gap-fill")
    @Operation(summary = "공고에서 못 채운 조건 채우기 시작",
            description = "맞춰 본 뒤 못 채운 필수 조건(없으면 우대 조건) 최대 5개를 AI 가 배경에서 찾기 시작하고 상세와 같은 응답을 돌려줍니다. AI 는 조건마다 찾아볼지 정하고, 카드로 만들지 않은 후보·작성 중 카드를 먼저 본 뒤 내 공개 커밋을 GitHub 에서 검색해 카드 만들기·카드 마저 채우기·레포 분석을 제안합니다. 사용자에게 묻지 않습니다. 찾는 중이거나 카드·후보·레포가 그대로인 공고는 다시 찾지 않고 호출 한도도 세지 않으며, 못 채운 조건이 없으면 AI 없이 DONE 입니다. 본문에 conditionIds 를 주면 끝난 결과에서 '찾지 않음'(result SKIPPED)으로 멈춘 그 조건만 AI 판단과 상관없이 찾고(forced), 나머지 조건은 앞 결과 그대로 둡니다. 화면은 gapFill.pollAfterMs 마다 상세 조회로 진행을 읽습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "채우기를 시작한 뒤의 상세(gapFill 은 보통 RUNNING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: conditionIds 에 끝난 결과에서 '찾지 않음' 으로 멈춘 조건이 아니거나 지금 못 채운 조건이 아닌 id 가 있음, 또는 본문을 읽을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "GITHUB_UNAUTHORIZED: 새로 찾아야 하는데 쓸 수 있는 GitHub 연결이 없음(저장한 결과를 다시 쓰거나 못 채운 조건이 없으면 연결을 보지 않음)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UUID 가 아니거나 없는 공고이거나 목록 대상이 아닌 공고",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 아직 맞춰 보지 않은 조건이 있음(needsJudgement), 또는 conditionIds 를 줬는데 끝난(DONE) 채우기 결과가 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "호출 한도를 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<MatchDetailResponse> fillGaps(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 공고 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody(required = false) MatchGapFillRequest request) {

        UUID postingId = toUuid(id);
        MatchDetail detail = request == null || request.conditionIds() == null || request.conditionIds().isEmpty()
                ? gapFills.start(postingId, loginUser.id(), loginUser.login())
                : gapFills.force(postingId, loginUser.id(), loginUser.login(), request.conditionIds());
        return ApiResponse.ok(withGapFill(detail, postingId, loginUser.id()));

    }

    private MatchDetailResponse withGapFill(MatchDetail detail, UUID postingId, Long userId) {

        return MatchDetailResponse.from(detail, gapFills.of(postingId, userId).orElse(null));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new MatchPostingNotFoundException();
        }

    }
}
