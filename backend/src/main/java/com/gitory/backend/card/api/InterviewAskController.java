package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.interview.InterviewAskService;
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
public class InterviewAskController {

    private final InterviewAskService interviewAsk;

    @PostMapping("/api/cards/{id}/interview")
    @Operation(summary = "되묻기 질문 요청", description = """
            카드의 한 칸(field)에 대해 AI 에게 질문을 받아 저장하고 그 질문을 돌려줍니다.
            답하지 않은 질문이 있으면 field 와 상관없이 새로 만들지 않고 그 질문을 그대로 돌려주며 AI 도 부르지 않습니다.
            후보로 만든 카드의 초안을 만드는 중이면 409 INVALID_STATE 이고 AI 도 부르지 않으며, 이 확인이 가장 먼저입니다.
            만든 질문 수가 상한에 닿았으면 409 INTERVIEW_CAP, 확정된 카드는 409 CARD_CONFIRMED 이고, 이 둘이 field 검사보다 먼저입니다.
            새 질문을 만들어야 하는데 field 가 S, T, A, R 이 아니면 400 BAD_FIELD 이고 AI 도 부르지 않습니다.
            AI 가 잠시 응답하지 못하면 503 AI_UNAVAILABLE 이고 질문은 만들어지지 않으니 다시 요청하면 됩니다.
            새 질문을 만들어야 하는데 사용자별 호출 한도를 넘었으면 위 확인을 모두 지난 뒤 429 RATE_LIMITED 이고 질문도 만들지 않고 AI 도 부르지 않습니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 질문, 또는 답하지 않은 채 남아 있던 질문"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "BAD_FIELD: field 가 없거나 S, T, A, R 이 아님, INVALID_REQUEST: 본문을 읽을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 초안을 만드는 중인 카드, CARD_CONFIRMED: 확정된 카드는 다시 열어야 질문할 수 있음, INTERVIEW_CAP: 만든 질문 수가 상한에 닿음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE: AI 가 잠시 응답하지 못해 질문을 만들지 못함",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 새 질문을 만들 사용자별 호출 한도를 넘음. details.retryAfterSec 와 Retry-After 헤더에 기다릴 초가 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<InterviewTurnResponse> ask(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody InterviewAskRequest request) {

        return ApiResponse.ok(InterviewTurnResponse.from(
                interviewAsk.ask(toUuid(id), loginUser.id(), request.field())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
