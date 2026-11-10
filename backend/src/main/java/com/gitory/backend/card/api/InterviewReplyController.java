package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.InterviewReplyService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Cards")
public class InterviewReplyController {

    private final InterviewReplyService interviewReply;

    @PostMapping("/api/cards/{id}/interview/{turnNo}/reply")
    @Operation(summary = "되묻기 답을 AI 가 읽고 카드에 넣기", description = """
            사용자가 친 답을 AI 가 먼저 읽고, 결과(outcome)에 따라 카드를 바꿉니다. 답을 그대로 칸에 넣는 답 저장 API 는 그대로 있고, 이 API 는 그 앞에서 답을 판단합니다.
            APPLIED 는 AI 가 쓴 문장을 물은 칸의 글로 넣은 INTERVIEW 새 버전을 만들고, 답에 함께 나온 내용이 있으면 지금 비어 있는 다른 칸에만 넣습니다. 질문은 답함으로 닫히고 사용자가 친 답은 다듬기 전 답(original)으로 남습니다.
            ASK_MORE 는 카드를 바꾸지 않고 그 질문을 모자란 답(INSUFFICIENT)으로 닫은 뒤 같은 칸의 꼬리 질문을 만들어 nextTurn 으로 돌려줍니다. 꼬리 질문에 답할 때는 앞서 둔 답이 AI 에 함께 가고 더 되묻지 않으며, 질문 상한에 닿았거나 같은 칸을 이미 두 번 물었으면 꼬리 질문을 만들지 않습니다.
            NOT_USED 는 상관없는 답이라 아무것도 저장하지 않고 질문을 열어 둡니다. SKIPPED 는 모른다는 답이라 카드를 바꾸지 않고 질문만 넘김으로 닫습니다.
            UNPOLISHED 는 AI 가 응답하지 못했거나 쓸 수 없는 문장을 줘서(칸에 못 담는 길이, 사용자 답·앞서 둔 답·그 칸의 기존 글에 없는 숫자 등) 아무것도 저장하지 않은 경우로, 오류가 아니라 200 입니다. 화면은 답 저장 API 로 그대로 넣게 할 수 있습니다.
            APPLIED 의 restoreVersionNo 를 버전 되돌리기 API 에 보내면 넣기 전 글로 돌아갑니다. 넣은 뒤 상황과 행동이 차 있고 확인 필요 칸이 없으면 카드가 확정됩니다.
            이미 끝난 질문에 같은 답이 다시 오면 아무것도 바꾸지 않고 처음 결과를 돌려주고(AI 를 부르지 않고 호출 한도에도 세지 않음), 다른 답이면 409 INVALID_STATE 입니다.
            확인 순서는 질문 번호(404), 초안을 만드는 중인 카드(409), 이미 끝난 질문(200·409), 답의 모양(400), 사용자별 호출 한도(429) 입니다. AI 를 기다리는 사이 카드의 현재 버전이 바뀌었으면 아무것도 저장하지 않고 409 INVALID_STATE 입니다.
            변경 요청과 같은 규칙으로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "AI 가 읽은 결과와 그 뒤의 카드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 질문 번호가 정수가 아님, 본문을 읽을 수 없음, source 가 USER_STATED·USER_SELECTED 가 아니거나 text 가 공백뿐이거나 1,000자를 넘거나 U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 본인 카드 없음 또는 그 번호의 질문 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 이미 다른 답으로 끝난 질문, 초안을 만드는 중인 카드, AI 를 기다리는 사이 카드가 바뀜",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 사용자별 호출 한도를 넘음. details.retryAfterSec 와 Retry-After 헤더에 기다릴 초가 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<InterviewReplyResponse> reply(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @Parameter(description = "질문 번호(되묻기 기록의 turnNo)")
            @PathVariable int turnNo,
            @RequestBody InterviewReplyRequest request) {

        return ApiResponse.ok(InterviewReplyResponse.from(
                interviewReply.reply(toUuid(id), loginUser.id(), turnNo, request.text(), request.source())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
