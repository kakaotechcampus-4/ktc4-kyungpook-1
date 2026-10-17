package com.gitory.backend.recommend.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.recommend.domain.CandidateBoardNotFoundException;
import com.gitory.backend.recommend.domain.CandidateBoardService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Candidates")
public class CandidateBoardController {

    private final CandidateBoardService boards;

    @GetMapping("/api/repos/{id}/candidates")
    @Operation(summary = "후보 보드 조회", description = "분석 뒤 저장해 둔 경험 후보를 점수 높은 순으로 돌려줍니다. 보드를 열 때 후보를 새로 만들지 않습니다. 남의 저장소는 없는 저장소와 똑같이 404 입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "후보 보드. 후보가 없으면 verdict=EMPTY"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CandidateBoardResponse> board(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 저장소 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(CandidateBoardResponse.from(boards.load(toUuid(id), loginUser.id(), loginUser.login())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CandidateBoardNotFoundException();
        }

    }
}
