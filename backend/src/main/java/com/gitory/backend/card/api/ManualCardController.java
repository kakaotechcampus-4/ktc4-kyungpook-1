package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.ManualCardService;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards", description = "본인 카드 작성과 조회")
public class ManualCardController {

    private final ManualCardService manualCards;

    @PostMapping("/api/cards/manual/draft")
    @Operation(summary = "직접 작성 카드 시작", description = """
            제목·기간·관련 저장소로 내용이 빈 작성 중 카드(1번 버전)를 만들고 카드 전체를 돌려줍니다.
            같은 Idempotency-Key 로 다시 요청하면 새로 만들지 않고 그 키로 만든 카드를 돌려주며, 다시 보낸 제목·기간·저장소는 쓰지 않습니다.
            키가 없거나 비었거나 공백뿐이면 요청마다 새로 만듭니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 카드 또는 같은 키로 이미 만든 카드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "제목이 비었거나 256자를 넘음, 기간이 50자를 넘음, 제목·기간에 U+0000 이 있음, Idempotency-Key 가 255자를 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "repoId 가 잘못된 UUID 이거나 본인 저장소가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardResponse> start(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @RequestBody ManualCardRequest request,
            @Parameter(description = "응답을 못 받고 다시 보내도 카드가 하나만 생기도록, 새 카드를 쓰기 시작할 때마다 만들어 보내는 255자까지의 키")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        return ApiResponse.ok(CardResponse.from(manualCards.start(loginUser.id(), request.title(), request.period(),
                repositoryId(request.repoId()), idempotencyKey)));

    }

    private UUID repositoryId(String repoId) {

        if (repoId == null) {
            return null;
        }

        try {
            return UUID.fromString(repoId);
        } catch (IllegalArgumentException e) {
            throw new ConnectedRepositoryNotFoundException();
        }

    }
}
