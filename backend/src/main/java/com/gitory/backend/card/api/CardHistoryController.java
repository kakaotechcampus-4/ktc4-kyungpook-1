package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardHistoryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class CardHistoryController {

    private final CardHistoryService cardHistory;

    @GetMapping("/api/cards/{id}/versions")
    @Operation(summary = "카드 작성 이력 조회", description = """
            카드의 버전을 최신 버전부터 돌려줍니다. current 는 지금 카드에 보이는 버전입니다.
            summary 는 그 버전에서 채운 칸을 S·T·A·R 글자로 적은 한 줄('S · A 채움', 채운 칸이 없으면 '채운 칸 없음')이고,
            카드의 첫 AI 초안에는 ' · 원본(잠김)', 확정하며 만든 버전에는 ' · 확정' 이 붙습니다. 출처 이름과 시각은 넣지 않습니다.
            남의 카드는 없는 카드와 똑같이 404 입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "버전 목록, 최신 버전부터"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 카드 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<VersionListItemResponse>> versions(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(cardHistory.history(toUuid(id), loginUser.id()).stream()
                .map(VersionListItemResponse::from)
                .toList());

    }

    @GetMapping("/api/cards/{id}/versions/{no}")
    @Operation(summary = "예전 버전 내용 조회", description = """
            고른 버전의 네 칸 글을 카드 상세의 version 과 같은 모양으로 돌려줍니다. 빈 칸은 null 이고, 글은 가리기 규칙과 상관없이 원문 그대로입니다.
            되돌리기 전에 지금 버전과 비교하는 데 씁니다. 지금 버전 번호를 골라도 그대로 돌려줍니다.
            남의 카드는 없는 카드와 똑같이 404 입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고른 버전의 네 칸"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "버전 번호가 정수가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 본인 카드 없음 또는 그 번호의 버전 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardVersionResponse> version(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @Parameter(description = "볼 버전 번호(작성 이력의 versionNo)")
            @PathVariable int no) {

        return ApiResponse.ok(CardVersionResponse.from(cardHistory.version(toUuid(id), loginUser.id(), no)));

    }

    @PostMapping("/api/cards/{id}/versions/{no}/restore")
    @Operation(summary = "예전 버전으로 되돌리기", description = """
            고른 버전의 네 칸을 근거째 그대로 복사한 RESTORE 새 버전을 현재 버전으로 삼고 카드 전체를 돌려줍니다. 고른 버전과 그 사이 버전은 이력에 그대로 남습니다.
            되살린 글로 상태를 다시 정합니다(임시 저장과 같은 규칙). 확정한 카드는 상황이나 행동이 비는 버전을 되살릴 때만 작성 중으로 돌아갑니다.
            지금 버전을 고르면 새 버전을 만들지 않고, 아무것도 바꾸지 않은 지금 카드를 돌려줍니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "되돌린 내용이 현재 버전인 카드, 지금 버전을 골랐으면 그대로인 카드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "버전 번호가 정수가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 본인 카드 없음 또는 그 번호의 버전 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardResponse> restore(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @Parameter(description = "되돌릴 버전 번호(작성 이력의 versionNo)")
            @PathVariable int no) {

        return ApiResponse.ok(CardResponse.from(cardHistory.restore(toUuid(id), loginUser.id(), no)));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
