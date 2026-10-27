package com.gitory.backend.job.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.job.domain.CardDraftRetryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class CardDraftRetryController {

    private final CardDraftRetryService retry;

    @PostMapping("/api/cards/{id}/draft/retry")
    @Operation(summary = "카드 AI 초안 다시 만들기", description = """
            AI 초안을 채우지 못한 카드(카드의 draftFailure 가 있음) 한 장으로 새 카드 초안 Job 을 만들어 다시 채우고, 그 Job 을 돌려줍니다.
            사용자가 이미 쓴 칸은 그대로 두고 나머지 칸만 채웁니다. 시작하면 카드의 draftFailure 는 지워지고, 또 실패하면 다시 남습니다.
            초안 실패가 없거나, 되묻기·확정으로 1번 버전을 지난 카드, 후보 없이 직접 쓴 카드는 409(INVALID_STATE), 확정된 카드는 409(CARD_CONFIRMED)입니다.
            카드 초안(CARD_AI_DRAFT_ENABLED)이 켜져 있으면 사용자별 하루 한도를 1 세고, 넘으면 아무것도 바꾸지 않고 429(RATE_LIMITED)입니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 카드 초안 Job id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 이거나 본인 카드가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 다시 만들 초안 실패가 없거나 1번 버전을 지난 카드, CARD_CONFIRMED: 확정된 카드",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 카드 초안이 켜져 있고 하루 남은 횟수가 없음. details.retryAfterSec 와 Retry-After 헤더에 기다릴 초가 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardDraftRetryResponse> retry(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드의 공개 UUID", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        UUID jobId = retry.retry(toUuid(id), loginUser.id());

        return ApiResponse.ok(new CardDraftRetryResponse(jobId.toString()));

    }

    private static UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
