package com.gitory.backend.coverletter.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.coverletter.domain.CoverLetterNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterQueryService;
import com.gitory.backend.coverletter.domain.CoverLetterService;
import com.gitory.backend.coverletter.domain.InvalidCoverLetterInputException;
import com.gitory.backend.coverletter.domain.NewAnswer;
import com.gitory.backend.coverletter.domain.NewQuestion;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cover letters", description = "확정 카드로 쓰는 자소서")
public class CoverLetterController {

    private final CoverLetterService coverLetters;
    private final CoverLetterQueryService coverLetterQuery;

    @PostMapping("/api/cover-letters")
    @Operation(summary = "자소서 만들기", description = """
            직무·메모·문항으로 자소서를 만들고 카드 고르기를 줄 세웁니다(stage ASSIGNING).
            같은 Idempotency-Key 로 다시 요청하면 보낸 내용과 상관없이 그 키로 만든 자소서를 돌려주고 호출 제한도 세지 않습니다.
            화면은 입력을 고치면 새 키를 만듭니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 자소서 또는 같은 키로 이미 만든 자소서"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 직무가 비었거나 100자를 넘음, 메모가 1,000자를 넘음, 문항이 0개거나 10개를 넘음, 문항이 비었거나 500자를 넘음, 글자 수가 100~5,000 이 아님, U+0000 이 있음, Idempotency-Key 가 255자를 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "NO_CONFIRMED_CARDS: 확정한 카드가 한 장도 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 만들기와 다시 시도를 합쳐 1분 2번, 하루 10번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @RequestBody CreateCoverLetterRequest request,
            @Parameter(description = "응답을 못 받고 다시 보내도 자소서가 한 편만 생기도록, 만들 때마다 새로 만들어 보내는 255자까지의 키")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        List<NewQuestion> questions = request.questions() == null ? null : request.questions().stream()
                .map(question -> question == null ? null : new NewQuestion(question.text(), question.charLimit()))
                .toList();
        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.create(loginUser.id(), request.jobRole(),
                request.memo(), questions, idempotencyKey)));

    }

    @GetMapping("/api/cover-letters/{id}")
    @Operation(summary = "자소서 조회", description = "AI 가 도는 단계(ASSIGNING·ASKING·DRAFTING)면 pollAfterMs 마다 다시 부릅니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 없는 자소서, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> get(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {

        return ApiResponse.ok(CoverLetterResponse.from(coverLetterQuery.load(toUuid(id), loginUser.id())));

    }

    @PutMapping("/api/cover-letters/{id}/questions/{questionId}/cards")
    @Operation(summary = "문항의 카드 바꾸기", description = """
            카드 고르기가 끝난 뒤(ASSIGNED)에만 바꿉니다. 내 확정 카드만 고를 수 있고 다른 문항과 겹쳐도 됩니다.
            바꾸면 assignedBy 가 USER 가 되고 AI 가 고른 이유는 지웁니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 카드 id 가 UUID 가 아님, 내 확정 카드가 아님, 중심과 보조가 같음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: ASSIGNED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> chooseCards(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId,
            @RequestBody ChooseCardsRequest request) {

        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.chooseCards(toUuid(id), toUuid(questionId),
                loginUser.id(), cardIdOf(request.primaryCardId()), cardIdOf(request.supportingCardId()))));

    }

    @PostMapping("/api/cover-letters/{id}/asks")
    @Operation(summary = "질문 만들기 시작", description = """
            카드 고르기가 끝난 뒤(ASSIGNED)에 지금 배정으로 질문 만들기를 줄 세웁니다(stage ASKING). 그 뒤로는 카드를 바꿀 수 없습니다.
            호출 제한은 세지 않습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: ASSIGNED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> startAsking(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {

        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.startAsking(toUuid(id), loginUser.id())));

    }

    @PutMapping("/api/cover-letters/{id}/questions/{questionId}/answers")
    @Operation(summary = "문항의 답 저장", description = """
            질문을 받은 뒤(ASKED)에 그 문항의 답 전체를 보낸 것으로 바꿉니다. 보내지 않은 항목은 답이 없는 것으로 남습니다.
            지난 답(question 이 null 인 줄)을 고치지 않고 다시 보내면 reused 가 true 로 남고, 고치면 false 가 됩니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 답 목록이 없음, 질문에 없거나 모르는 항목, 같은 항목이 두 번, 넘기지 않았는데 글이 비었거나 1,000자를 넘거나 U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: ASKED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> saveAnswers(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId,
            @RequestBody SaveAnswersRequest request) {

        List<NewAnswer> answers = request.answers() == null ? null : request.answers().stream()
                .map(answer -> answer == null ? null
                        : new NewAnswer(answer.item(), answer.text(), Boolean.TRUE.equals(answer.skipped())))
                .toList();
        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.answer(toUuid(id), toUuid(questionId),
                loginUser.id(), answers)));

    }

    @PostMapping("/api/cover-letters/{id}/retry")
    @Operation(summary = "실패한 단계 다시 시도", description = "실패한 자소서(FAILED)의 실패한 단계만 다시 줄 세웁니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: FAILED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 만들기와 다시 시도를 합쳐 1분 2번, 하루 10번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> retry(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {

        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.retry(toUuid(id), loginUser.id())));

    }

    private static UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CoverLetterNotFoundException();
        }

    }

    private static UUID cardIdOf(String id) {

        if (id == null) {
            return null;
        }

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidCoverLetterInputException("카드 id 가 올바르지 않습니다.");
        }

    }
}
