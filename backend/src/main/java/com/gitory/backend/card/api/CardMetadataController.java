package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardMetadataService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class CardMetadataController {

    private final CardMetadataService cardMetadata;

    @PatchMapping("/api/cards/{id}/metadata")
    @Operation(summary = "카드 제목·기간 수정", description = """
            보낸 칸만 고칩니다. 키가 없거나 null 인 칸은 그대로 두고, 기간을 빈 글이나 공백뿐인 글로 보내면 기간을 지웁니다.
            제목은 공백뿐이면 안 되고 256자까지, 기간은 50자까지이며 둘 다 받은 글 그대로 저장합니다.
            보낸 값이 모두 지금 값과 같으면(기간이 없는 카드에 빈 기간을 보낸 경우 포함) 아무것도 바꾸지 않고 updatedAt 도 그대로인 지금 값을 돌려줍니다.
            제목과 기간을 모두 보내지 않으면 400 입니다. 네 칸·근거·버전은 바꾸지 않고, 확정한 카드도 다시 열지 않고 고칩니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 뒤의 제목·기간과 카드를 마지막으로 바꾼 시각"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 바꿀 칸이 없음, 제목이 공백뿐이거나 256자를 넘음, 기간이 50자를 넘음, 제목·기간에 U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardMetadataResponse> edit(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody CardMetadataRequest request) {

        return ApiResponse.ok(CardMetadataResponse.from(
                cardMetadata.edit(toUuid(id), loginUser.id(), request.title(), request.period())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
