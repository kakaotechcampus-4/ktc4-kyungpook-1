package com.gitory.backend.ingest.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.ingest.domain.CommitSearchService;
import com.gitory.backend.ingest.domain.ConnectedRepositoryNotFoundException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Repos")
public class RepoCommitController {

    private final CommitSearchService commitSearch;

    @GetMapping("/api/repos/{id}/commits")
    @Operation(summary = "내 커밋 검색", description = "분석 때 수집한 커밋 중 내 커밋을 메시지(대소문자 무시) 또는 커밋 번호 앞부분으로 찾아 최근 순으로 최대 20개 돌려줍니다. 분석 전이거나 검색어가 비면 빈 배열입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "찾은 커밋 배열. 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<RepoCommitResponse>> commits(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 저장소 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @Parameter(description = "검색어. 커밋 메시지에 들어 있거나 커밋 번호가 이 글자로 시작하면 찾는다")
            @RequestParam(defaultValue = "") String q) {

        return ApiResponse.ok(commitSearch.search(toUuid(id), loginUser.id(), loginUser.login(), q).stream()
                .map(RepoCommitResponse::from)
                .toList());

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ConnectedRepositoryNotFoundException();
        }

    }
}
