package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.InterviewMeasuredDraftService;
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
public class InterviewMeasuredDraftController {

    private final InterviewMeasuredDraftService measuredDraft;

    @PostMapping("/api/cards/{id}/interview/{turnNo}/measured-draft")
    @Operation(summary = "'재 봤어요' 수치 문장 미리 보기", description = """
            R 수치 질문에 자유롭게 쓴 측정 결과로 R 문장을 만들어 돌려주고, 아무것도 저장하지 않습니다. 확정은 답 저장 API 로 합니다.
            측정 결과에 숫자가 없으면 AI 를 부르지 않고 sentence 가 null 이며, 화면은 수치를 대략이라도 적어 달라고 안내합니다.
            AI 가 쓴 문장에 측정 결과에 없는 숫자가 있거나 AI 가 응답하지 못하면 지금 R 문장 뒤에 측정 결과를 붙인 문장을 돌려줍니다.
            확인 순서는 질문 번호(404), 초안을 만드는 중인 카드(409 INVALID_STATE), 확정된 카드(409 CARD_CONFIRMED), 결과가 정해진 질문(409 INVALID_STATE), R 수치 질문이 아님(400 BAD_FIELD), 글의 모양(400) 입니다.
            AI 를 불러야 할 때 사용자별 호출 한도(답 평가와 같은 한도)를 넘었으면 429 RATE_LIMITED 입니다. 질문 상한에는 세지 않습니다.
            변경 요청과 같은 규칙으로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수치를 넣은 R 문장, 또는 측정 결과에 숫자가 없으면 sentence 가 null"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "BAD_FIELD: R 수치 질문이 아님, INVALID_REQUEST: text 가 없거나 공백뿐이거나 1,000자를 넘거나 U+0000 이 있음",
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
    public ApiResponse<MeasuredDraftResponse> draft(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @Parameter(description = "질문 번호(되묻기 기록의 turnNo)")
            @PathVariable int turnNo,
            @RequestBody MeasuredDraftRequest request) {

        return ApiResponse.ok(new MeasuredDraftResponse(
                measuredDraft.draft(toUuid(id), loginUser.id(), turnNo, request.text())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
