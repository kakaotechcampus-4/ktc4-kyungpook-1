package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.CardReopenService;
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
public class CardReopenController {

    private final CardReopenService cardReopen;

    @PostMapping("/api/cards/{id}/reopen")
    @Operation(summary = "확정한 카드 다시 열기", description = """
            확정한 카드를 작성 중으로 되돌리고 확정 시각을 지운 뒤 카드 전체를 돌려줍니다.
            새 버전은 만들지 않아 확정한 버전이 그대로 현재 버전이고, 이미 작성 중인 카드면 아무것도 바꾸지 않고 지금 카드를 돌려줍니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "작성 중으로 돌린 카드 또는 이미 작성 중이던 카드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardResponse> reopen(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(CardResponse.from(cardReopen.reopen(toUuid(id), loginUser.id())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
