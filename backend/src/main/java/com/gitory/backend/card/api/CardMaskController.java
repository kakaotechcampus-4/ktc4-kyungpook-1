package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardMaskService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class CardMaskController {

    private final CardMaskService cardMask;

    @PostMapping("/api/cards/{id}/mask")
    @Operation(summary = "정보 가리기 규칙 저장", description = """
            가릴 말(from)을 바꿀 말(to)로 보여 주는 규칙을 받은 순서 그대로 저장하고 카드 전체를 돌려줍니다. 원문은 바꾸지 않고, 가린 글은 화면이 규칙으로 만듭니다.
            규칙이 바뀌면 네 칸을 그대로 복사한 MASK 새 버전을 만들고, 저장된 규칙과 순서까지 같으면 버전을 만들지 않습니다.
            규칙은 20개까지, from·to 는 각각 100자까지이며 앞뒤 공백도 그대로 저장합니다. from 은 공백뿐이면 안 되고 to 는 빈 글이어도 됩니다.
            확정한 카드도 다시 열지 않고 바꿉니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "규칙을 저장한 카드 또는 규칙이 그대로인 카드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "규칙 목록이 없음, 21개 이상, from 이 공백뿐이거나 from·to 가 없음, 100자 초과, from·to 에 U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardResponse> mask(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody CardMaskRequest request) {

        return ApiResponse.ok(CardResponse.from(cardMask.mask(toUuid(id), loginUser.id(), request.rules())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
