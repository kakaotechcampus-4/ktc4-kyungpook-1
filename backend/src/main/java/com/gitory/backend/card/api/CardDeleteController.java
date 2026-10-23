package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardDeleteService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class CardDeleteController {

    private final CardDeleteService cardDelete;

    @DeleteMapping("/api/cards/{id}")
    @Operation(summary = "카드 삭제", description = """
            작성 중 카드와 확정 카드 모두 버전·문장·근거·칸 메모·되묻기 질문까지 통째로 지우며, 되돌릴 수 없습니다.
            후보로 만든 카드면 그 후보는 NEW 로 돌아가 후보 보드에 다시 나타나고, 같은 후보로 카드를 다시 만들 수 있습니다.
            오류 순서는 401 → 403 → 404 → 409 입니다. 이미 지운 카드는 없는 카드라 404 이고, 초안을 만드는 중인 카드는 409 INVALID_STATE 입니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "지움. data 는 { ok: true }"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 초안을 만드는 중인 카드",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardDeleteResponse> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        cardDelete.delete(toUuid(id), loginUser.id());

        return ApiResponse.ok(new CardDeleteResponse(true));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
