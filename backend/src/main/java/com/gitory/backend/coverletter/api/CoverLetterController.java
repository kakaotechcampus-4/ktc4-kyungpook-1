package com.gitory.backend.coverletter.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.coverletter.domain.CoverLetterNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterPolishService;
import com.gitory.backend.coverletter.domain.CoverLetterPostingNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterQueryService;
import com.gitory.backend.coverletter.domain.CoverLetterService;
import com.gitory.backend.coverletter.domain.CoverLetterTurnService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final CoverLetterPolishService polishes;
    private final CoverLetterTurnService turns;
    private final CoverLetterQueryService coverLetterQuery;

    @PostMapping("/api/cover-letters")
    @Operation(summary = "자소서 만들기", description = """
            직무·지원 이유·강점·문항으로 자소서를 만들고 카드 고르기를 줄 세웁니다(stage ASSIGNING).
            공고 상세에서 시작하면 postingId 를 보내며, 그때의 공고와 맞춰 본 판단을 자소서에 남겨 카드 고르기·질문·초안이 그 기준으로 AI 를 부릅니다.
            같은 Idempotency-Key 로 다시 요청하면 보낸 내용과 상관없이 그 키로 만든 자소서를 돌려주고 호출 제한도 세지 않습니다.
            화면은 입력을 고치면 새 키를 만듭니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 자소서 또는 같은 키로 이미 만든 자소서"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 직무가 비었거나 100자를 넘음, 지원 이유가 규칙(공백 뺀 20자 이상·1,000자 이하·한글 포함·5번 이상 같은 글자 반복 없음·URL/이메일 없음·한글/영문/숫자/문장부호만)을 어김, 강점이 3개를 넘거나 겹치거나 모르는 값임, 문항이 0개거나 10개를 넘음, 문항이 비었거나 500자를 넘음, 글자 수가 100~5,000 이 아님, U+0000 이 있음, Idempotency-Key 가 255자를 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND: postingId 가 UUID 가 아니거나 공고 상세에서 열 수 없는 공고",
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
                request.memo(), request.strengths(), questions, postingIdOf(request.postingId()), idempotencyKey)));

    }

    @GetMapping("/api/cover-letters")
    @Operation(summary = "내 자소서 목록", description = "최근에 만든 자소서부터, 문항 수와 첫 문항을 함께 돌려줍니다. 없으면 빈 배열입니다.")
    public ApiResponse<List<CoverLetterSummaryResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(coverLetterQuery.list(loginUser.id()).stream()
                .map(CoverLetterSummaryResponse::from)
                .toList());

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

    @PostMapping("/api/cover-letters/{id}/drafts")
    @Operation(summary = "초안 쓰기 시작", description = """
            질문을 받은 뒤(ASKED)에 지금 답으로 초안 쓰기를 줄 세웁니다(stage DRAFTING). 답하지 않은 질문은 넘긴 것으로 봅니다.
            이때 상황·행동·결과·배운 점·계기 답을 중심 카드에 기억해, 같은 카드를 쓰는 다음 자소서에 미리 채웁니다(넘긴 항목은 전에 기억한 답을 지우지 않음).
            호출 제한은 세지 않습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: ASKED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> startDrafting(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {

        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.startDrafting(toUuid(id), loginUser.id())));

    }

    @PatchMapping("/api/cover-letters/{id}/questions/{questionId}")
    @Operation(summary = "초안 고치기", description = """
            초안을 받은 뒤(DRAFTED)에 문항의 글을 고친 글로 바꿉니다. AI 원본(aiText)·문장 번호(sentences)·확인 필요 표시(marks)는 그대로입니다.
            charCount·overBy·blanks 는 고친 글로 다시 세고, 고친 글이 AI 원본과 다르면 edited 가 true 입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 글이 없음, 10,000자를 넘음, U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> editDraft(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId,
            @RequestBody EditDraftRequest request) {

        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.editDraft(toUuid(id), toUuid(questionId),
                loginUser.id(), request.text())));

    }

    @DeleteMapping("/api/cover-letters/{id}/questions/{questionId}/marks/{sentenceSeq}")
    @Operation(summary = "AI 추론 문장 확인", description = """
            초안을 받은 뒤(DRAFTED)에 사용자가 맞다고 확인한 AI 추론 문장의 표시(marks 의 code INFERRED)만 지웁니다.
            글(text·aiText)과 다른 표시는 그대로이며, 응답은 초안 고치기와 같은 자소서 전체입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님, 그 문장 번호에 INFERRED 표시가 없음(이미 확인함·다른 표시만 있음)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> confirmInference(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId,
            @Parameter(description = "확인한 문장의 번호(sentences 의 seq)") @PathVariable int sentenceSeq) {

        return ApiResponse.ok(CoverLetterResponse.from(coverLetters.confirmInference(toUuid(id), toUuid(questionId),
                loginUser.id(), sentenceSeq)));

    }

    @PostMapping("/api/cover-letters/{id}/questions/{questionId}/polish")
    @Operation(summary = "문항 첨삭 받기", description = """
            초안을 받은 뒤(DRAFTED)에 문항의 지금 글(text)을 진단하고 고쳐 쓴 글을 받아 문항의 polish 에 둡니다. 앞 첨삭 결과는 덮습니다.
            AI 를 기다렸다가 답하므로 수십 초 걸릴 수 있고, 지금 글·문장·표시는 적용하기 전까지 그대로입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 지금 글이 비었음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 첨삭 받기가 1분 3번, 하루 30번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE: AI 가 잠시 응답하지 못함. 저장한 것이 없어 다시 요청해도 된다",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> polish(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId) {

        return ApiResponse.ok(CoverLetterResponse.from(polishes.polish(toUuid(id), toUuid(questionId), loginUser.id())));

    }

    @PostMapping("/api/cover-letters/{id}/questions/{questionId}/polish/apply")
    @Operation(summary = "문항 첨삭 적용", description = """
            첨삭한 글로 문항의 지금 글(text)·문장(sentences)·확인 필요 표시(marks)·빈칸·글자 수를 바꾸고 polish 를 비웁니다.
            edited 는 false, polishAppliedCount 는 1 늘고, AI 원본(aiText)은 그대로입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님, 적용할 첨삭 결과가 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> applyPolish(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId) {

        return ApiResponse.ok(CoverLetterResponse.from(polishes.applyPolish(toUuid(id), toUuid(questionId),
                loginUser.id())));

    }

    @DeleteMapping("/api/cover-letters/{id}/questions/{questionId}/polish")
    @Operation(summary = "문항 첨삭 버리기", description = "문항의 첨삭 결과(polish)를 비웁니다. 없으면 그대로 돌려줍니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterResponse> discardPolish(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId) {

        return ApiResponse.ok(CoverLetterResponse.from(polishes.discardPolish(toUuid(id), toUuid(questionId),
                loginUser.id())));

    }

    @GetMapping("/api/cover-letters/{id}/turns")
    @Operation(summary = "채우기 대화 읽기", description = """
            자소서 하나의 채우기 대화 전체를 오래된 말부터 돌려줍니다. 말마다 어느 문항 이야기인지 questionId 를 답니다.
            어느 단계에서든 읽을 수 있고, 대화가 없으면 빈 배열입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 없는 자소서, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterTurnsResponse> listTurns(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {

        return ApiResponse.ok(new CoverLetterTurnsResponse(turns.turns(toUuid(id), loginUser.id())));

    }

    @PostMapping("/api/cover-letters/{id}/questions/{questionId}/turns/open")
    @Operation(summary = "문항 이야기 시작", description = """
            초안을 받은 뒤(DRAFTED)에 그 문항의 빈 재료를 묻는 AI 의 첫 말을 받아 대화에 더합니다.
            AI 를 기다렸다가 답하므로 수십 초 걸릴 수 있습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 이야기 시작·보내기·넘기기를 합쳐 1분 6번, 하루 60번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE: AI 가 잠시 응답하지 못함. 아무것도 저장하지 않아 다시 요청해도 된다",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterTurnResponse> openTurn(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId) {

        return ApiResponse.ok(CoverLetterTurnResponse.from(turns.open(toUuid(id), toUuid(questionId), loginUser.id())));

    }

    @PostMapping("/api/cover-letters/{id}/questions/{questionId}/turns")
    @Operation(summary = "대화에 답 보내기", description = """
            초안을 받은 뒤(DRAFTED)에 사용자의 말과 AI 의 다음 말을 대화에 더합니다.
            AI 가 채울 수 있는 답이라고 하면(status FILLED) 그 답을 문항 답에 저장하고(같은 항목에 앞 답이 있으면 뒤에 덧붙이고 1,000자에서 자름), 상황·행동·결과·배운 점·계기면 중심 카드에도 기억한 뒤,
            그 답으로 문항의 지금 글만 다시 써 text·sentences·marks·blanks·글자 수를 바꿉니다(edited 는 false, aiText 는 그대로, 첨삭 결과는 비움).
            다시 쓰기에 실패했거나 AI 가 다시 쓴 글을 되돌렸거나 그사이 사용자가 글을 고쳤으면 대화와 답은 남기고 글은 그대로 두며 rewriteFailed 가 true 입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 말이 없거나 공백뿐임, 500자를 넘음, U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 이야기 시작·보내기·넘기기를 합쳐 1분 6번, 하루 60번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE: AI 가 잠시 응답하지 못함. 보낸 말도 저장하지 않아 다시 보내도 된다",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterTurnResponse> sendTurn(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId,
            @RequestBody SendTurnRequest request) {

        return ApiResponse.ok(CoverLetterTurnResponse.from(turns.reply(toUuid(id), toUuid(questionId), loginUser.id(),
                request.text())));

    }

    @PostMapping("/api/cover-letters/{id}/questions/{questionId}/turns/skip")
    @Operation(summary = "질문 넘기기", description = """
            초안을 받은 뒤(DRAFTED)에 지금 질문을 넘기고, AI 의 다음 말(다음 빈 재료를 묻거나 마무리)을 대화에 더합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 자소서·문항, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: DRAFTED 가 아님",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 이야기 시작·보내기·넘기기를 합쳐 1분 6번, 하루 60번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE: AI 가 잠시 응답하지 못함. 아무것도 저장하지 않아 다시 요청해도 된다",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterTurnResponse> skipTurn(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @Parameter(description = "문항 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String questionId) {

        return ApiResponse.ok(CoverLetterTurnResponse.from(turns.skip(toUuid(id), toUuid(questionId), loginUser.id())));

    }

    @DeleteMapping("/api/cover-letters/{id}")
    @Operation(summary = "자소서 지우기", description = """
            어느 단계에서든 자소서와 문항·답·초안을 지웁니다. AI 가 돌던 단계의 결과는 저장하지 않고 버립니다.
            카드에 기억한 답은 다른 자소서도 쓰므로 그대로 둡니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "지움"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 없는 자소서, 남의 자소서",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CoverLetterDeleteResponse> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "자소서 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {

        coverLetters.delete(toUuid(id), loginUser.id());

        return ApiResponse.ok(new CoverLetterDeleteResponse(true));

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

    private static UUID postingIdOf(String id) {

        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CoverLetterPostingNotFoundException();
        }

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
