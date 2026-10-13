package com.gitory.backend.recommend.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.recommend.domain.CandidateBoardNotFoundException;
import com.gitory.backend.recommend.domain.RecallAnswerService;
import com.gitory.backend.recommend.domain.RecallService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Candidates")
public class RecallController {

    private final RecallService recalls;
    private final RecallAnswerService answers;

    @GetMapping("/api/repos/{id}/recall")
    @Operation(summary = "회상 도우미 폴더 목록", description = "저장해 둔 내 커밋이 바꾼 파일을 폴더 끝 두 단계로 묶어, 내 커밋이 많은 폴더 5개를 질문과 함께 돌려줍니다. GitHub 를 부르지 않고 DB 만 읽으며 성공은 감사 기록을 남기지 않습니다. 남의 저장소는 없는 저장소와 똑같이 404 입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "폴더 목록. 파일이 저장된 내 커밋이 없으면 dirs 가 빈 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<RecallResponse> recall(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 저장소 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        return ApiResponse.ok(RecallResponse.from(recalls.load(toUuid(id), loginUser.id(), loginUser.login())));

    }

    @PostMapping("/api/repos/{id}/recall/answers")
    @Operation(summary = "회상 답을 직접 추가 후보로 저장", description = """
            폴더에 쓴 답을 직접 추가(MANUAL) 후보로 저장하고 보드의 후보 한 줄과 같은 모양으로 돌려줍니다.
            같은 폴더와 같은 답으로 만든 새 후보가 이미 있으면 새로 만들지 않고 그 후보를 돌려줍니다.
            남의 저장소는 없는 저장소와 똑같이 404 이고 거절 기록이 남으며, 입력 검사는 그다음입니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 후보 또는 같은 답으로 이미 만든 후보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "본문이 없거나 모양이 틀림, path 가 비었거나 197자를 넘음, text 가 비었거나 300자를 넘음, 글에 U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 저장소 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CandidateResponse> answer(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "목록 응답의 저장소 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody RecallAnswerRequest request) {

        UUID repositoryId = toUuid(id);

        return ApiResponse.ok(CandidateResponse.from(repositoryId.toString(),
                answers.answer(repositoryId, loginUser.id(), request.path(), request.text())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CandidateBoardNotFoundException();
        }

    }
}
