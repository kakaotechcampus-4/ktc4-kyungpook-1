package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.DraftSaveService;
import com.gitory.backend.card.domain.StarField;
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

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class DraftSaveController {

    private static final Map<String, StarField> SLOT_KEYS = Map.of(
            "situation", StarField.S, "task", StarField.T, "action", StarField.A, "result", StarField.R);

    private final DraftSaveService draftSave;

    @PatchMapping("/api/cards/{id}/draft")
    @Operation(summary = "카드 임시 저장", description = """
            네 칸을 저장합니다. 확정본이 아닌 직접 수정(USER_EDIT) 현재 버전은 덮어쓰고, 그 밖의 버전 위에서는
            첫 저장 때 USER_EDIT 새 버전을 만듭니다. 네 칸이 현재 버전과 같으면 아무것도 바꾸지 않고 현재 버전 번호를 돌려줍니다.
            네 키를 모두 보내야 하고 비운 칸은 null 로 보냅니다. 칸마다 1,000자까지 저장합니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = DraftFieldsRequest.class))))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장된 버전 번호와 네 칸"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 네 키 중 빠진 키가 있거나, 1,000자를 넘거나 U+0000 이 든 칸이 있거나, 본문을 읽을 수 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "CARD_CONFIRMED: 확정된 카드는 다시 열어야 수정할 수 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardDraftResponse> save(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody Map<String, String> fields) {

        return ApiResponse.ok(CardDraftResponse.from(draftSave.save(toUuid(id), loginUser.id(), textsOf(fields))));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }

    /**
     * 자바 record 는 키가 없는 것과 null 을 가르지 못해 본문을 Map 으로 받고, 문서에는 DraftFieldsRequest 모양을 보여 준다
     * 본문에 없는 키는 넣지 않아, 서비스가 남의 카드·확정 카드를 먼저 확인한 뒤 빠진 칸을 거절하게 한다
     */
    private static Map<StarField, String> textsOf(Map<String, String> fields) {

        Map<StarField, String> texts = new EnumMap<>(StarField.class);
        SLOT_KEYS.forEach((key, field) -> {
            if (fields.containsKey(key)) {
                texts.put(field, fields.get(key));
            }
        });

        return texts;

    }
}
