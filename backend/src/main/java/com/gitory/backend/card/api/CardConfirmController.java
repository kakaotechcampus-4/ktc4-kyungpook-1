package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardConfirmService;
import com.gitory.backend.card.domain.CardNotFoundException;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class CardConfirmController {

    private final CardConfirmService cardConfirm;

    @PostMapping("/api/cards/{id}/confirm")
    @Operation(summary = "카드 확정", description = """
            현재 버전을 그대로 복사한 확정본을 새 버전으로 만들고 카드를 확정합니다. 상황(S)이나 행동(A)이 비어 있으면 422 입니다.
            이미 확정된 카드면 새 버전을 만들지 않고 지금 확정 결과를 돌려줍니다.
            화면이 보내는 본문(edited·maskedFields)은 읽지 않습니다. 변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확정 결과. 이미 확정된 카드면 지금 확정 결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "NOT_CONFIRMABLE: 상황(S)이나 행동(A)이 비어 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<ConfirmResultResponse> confirm(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(ConfirmResultResponse.from(cardConfirm.confirm(toUuid(id), loginUser.id())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
