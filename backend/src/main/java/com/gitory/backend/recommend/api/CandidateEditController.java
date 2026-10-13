package com.gitory.backend.recommend.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.recommend.domain.CandidateAddService;
import com.gitory.backend.recommend.domain.CandidateBoardNotFoundException;
import com.gitory.backend.recommend.domain.CandidateEditService;
import com.gitory.backend.recommend.domain.CandidateNotFoundException;
import com.gitory.backend.recommend.domain.EditedCandidate;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Candidates")
public class CandidateEditController {

    private final CandidateEditService edits;
    private final CandidateAddService adds;

    @PatchMapping("/api/candidates/{id}")
    @Operation(summary = "후보 제외·복원·커밋 빼기", description = """
            후보를 제외하거나 복원하고, 커밋 묶음 후보에서는 뺄 커밋을 정해 보드의 후보 한 줄과 같은 모양으로 돌려줍니다.
            이미 그 상태여도 200 이고, 성공한 수정은 모두 사용자가 손댄 후보로 표시합니다.
            남의 후보는 없는 후보와 똑같이 404 이고 거절 기록이 남으며, 카드로 쓴 후보는 409, 입력 검사는 그다음입니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 뒤의 후보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "본문이 없거나 모양이 틀림, status 와 excludedShas 가 둘 다 없음, status 가 NEW·EXCLUDED 가 아님, 커밋 묶음이 아닌 후보에 excludedShas, 후보의 커밋이 아닌 sha",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 후보 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 이미 카드로 쓴 후보",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CandidateResponse> edit(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "후보 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody CandidatePatchRequest request) {

        EditedCandidate edited = edits.edit(candidateIdOf(id), loginUser.id(), request.status(),
                request.excludedShas());

        return ApiResponse.ok(CandidateResponse.from(edited.repositoryId().toString(), edited.candidate()));

    }

    @PostMapping("/api/repos/{id}/candidates")
    @Operation(summary = "후보 직접 추가", description = """
            제목·요약과 고른 내 커밋으로 직접 추가(MANUAL) 후보를 만들어 보드의 후보 한 줄과 같은 모양으로 돌려줍니다.
            같은 제목·요약·커밋으로 만든 새 후보가 이미 있으면 새로 만들지 않고 그 후보를 돌려줍니다.
            남의 저장소는 없는 저장소와 똑같이 404 이고 거절 기록이 남으며, 입력 검사는 그다음입니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 후보 또는 같은 글로 이미 만든 후보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "본문이 없거나 모양이 틀림, 제목이 비었거나 200자를 넘음, 요약이 비었거나 300자를 넘음, 글에 U+0000 이 있음, 이 저장소의 내 커밋이 아닌 sha, sha 가 1000개를 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CandidateResponse> add(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 저장소 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody CandidateAddRequest request) {

        UUID repositoryId = repositoryIdOf(id);

        return ApiResponse.ok(CandidateResponse.from(repositoryId.toString(),
                adds.add(repositoryId, loginUser.id(), loginUser.login(), request.title(), request.summary(),
                        request.shas())));

    }

    private UUID candidateIdOf(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CandidateNotFoundException();
        }

    }

    private UUID repositoryIdOf(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CandidateBoardNotFoundException();
        }

    }
}
