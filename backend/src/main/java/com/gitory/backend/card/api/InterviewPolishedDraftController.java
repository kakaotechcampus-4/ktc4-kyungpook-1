package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.InterviewPolishedDraftService;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class InterviewPolishedDraftController {

    private final InterviewPolishedDraftService polishedDraft;

    @PostMapping("/api/cards/{id}/interview/{turnNo}/polished-draft")
    @Operation(summary = "경험 카드 답 다듬기 미리 보기", description = """
            경험 카드의 글 질문에 쓴 답을 AI 가 표현만 다듬은 문장으로 돌려주고, 아무것도 저장하지 않습니다. 저장은 답 저장 API 에 다듬은 문장과 original 로 합니다.
            AI 는 답에 없는 사실을 더하지 않도록 지시받고, 다듬은 문장에 답에 없는 숫자가 있으면 unsourcedNumbers 로 알려 화면이 강조하게 합니다.
            AI 가 응답하지 못하면 오류가 아니라 polished 가 false 이고 sentence 가 사용자 답 그대로입니다.
            확인 순서는 질문 번호(404), 초안을 만드는 중인 카드(409 INVALID_STATE), 확정된 카드(409 CARD_CONFIRMED), 결과가 정해진 질문(409 INVALID_STATE), 경험 카드의 글 질문이 아님(400 BAD_FIELD), 글의 모양(400) 입니다.
            사용자별 호출 한도('재 봤어요' 미리 보기와 같은 한도)를 넘었으면 429 RATE_LIMITED 입니다. 질문 상한에는 세지 않습니다.
            변경 요청과 같은 규칙으로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "다듬은 문장(다듬지 못했으면 사용자 답)과 답에 없는 숫자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "BAD_FIELD: 경험 카드의 글 질문이 아님, INVALID_REQUEST: text 가 없거나 공백뿐이거나 1,000자를 넘거나 U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 본인 카드 없음 또는 그 번호의 질문 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 초안을 만드는 중인 카드 또는 결과가 정해진 질문, CARD_CONFIRMED: 확정된 카드",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 사용자별 호출 한도를 넘음. details.retryAfterSec 와 Retry-After 헤더에 기다릴 초가 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<PolishedDraftResponse> draft(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @Parameter(description = "질문 번호(되묻기 기록의 turnNo)")
            @PathVariable int turnNo,
            @RequestBody PolishedDraftRequest request) {

        return ApiResponse.ok(PolishedDraftResponse.from(
                polishedDraft.draft(toUuid(id), loginUser.id(), turnNo, request.text())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
