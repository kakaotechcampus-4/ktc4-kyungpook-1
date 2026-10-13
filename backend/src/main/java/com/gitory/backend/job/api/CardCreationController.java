package com.gitory.backend.job.api;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.job.domain.CardCreationService;
import com.gitory.backend.job.domain.CreatedCards;
import com.gitory.backend.job.domain.RepositoryNotFoundException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Repositories")
public class CardCreationController {

    private final RepositoryOwnershipService ownership;
    private final CardCreationService cardCreation;

    @PostMapping("/api/repos/{id}/cards")
    @Operation(summary = "후보로 카드 만들기", description = """
            고른 후보마다 카드(1번 버전, 출처 직접 수정)를 만들고 후보를 카드로 쓴 상태로 바꾸며, 카드를 묶는 카드 초안 Job 하나를 돌려줍니다.
            포함된 커밋이 없는 후보는 정성 카드, 있으면 기술 카드로 시작하고 제목은 후보 제목이며 기간은 비워 둡니다.
            직접 추가하거나 회상으로 만든 후보는 그 글을 과제(T) 칸에 사용자 진술로 넣고 나머지 칸은 비워 둡니다.
            같은 후보를 여러 번 보내면 한 번으로 세고, 하나라도 이 저장소의 후보가 아니면 아무것도 만들지 않고 404, 이미 카드로 쓴 후보나 제외한 후보가 섞여 있으면 409(INVALID_STATE)입니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "만든 카드 id(보낸 순서)와 초안 Job id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "NO_CANDIDATES: 후보를 보내지 않았거나 빈 목록",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 이거나 본인 저장소가 아님, 또는 이 저장소의 후보가 아닌 id 가 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 이미 카드로 쓴 후보나 제외한 후보가 섞여 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardCreationResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "본인이 선택한 저장소의 공개 UUID", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody(required = false) CardCreationRequest request) {

        Long userRepositoryId = ownership.findOwnedRepository(toUuid(id), loginUser.id(), AuditAction.CREATE_CARD)
                .orElseThrow(RepositoryNotFoundException::new);
        CreatedCards created = cardCreation.create(loginUser.id(), userRepositoryId, candidateIdsOf(request));

        return ApiResponse.ok(new CardCreationResponse(created.jobId().toString(),
                created.cardIds().stream().map(UUID::toString).toList()));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new RepositoryNotFoundException();
        }

    }

    // 형식이 잘못된 후보 id 는 없는 후보와 같게 다루려고 null 로 넘긴다
    private static List<UUID> candidateIdsOf(CardCreationRequest request) {

        if (request == null || request.candidateIds() == null) {
            return List.of();
        }

        List<UUID> ids = new ArrayList<>();
        for (String candidateId : request.candidateIds()) {
            ids.add(uuidOrNull(candidateId));
        }

        return ids;

    }

    private static UUID uuidOrNull(String value) {

        if (value == null) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }

    }
}
