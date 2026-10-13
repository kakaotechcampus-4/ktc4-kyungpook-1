package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.InterviewAnswerService;
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
public class InterviewAnswerController {

    private final InterviewAnswerService interviewAnswer;

    @PostMapping("/api/cards/{id}/interview/{turnNo}/answer")
    @Operation(summary = "되묻기 답변 저장", description = """
            질문에 쓴 답을 가공 없이 그 질문의 칸 글로 저장하고, 그 칸만 바꾼 INTERVIEW 새 버전을 현재 버전으로 삼아 카드 전체를 돌려줍니다.
            답한 칸은 옛 글의 근거 커밋과 확인 필요 표시를 잃고 사용자가 쓴 글(USER_STATED)이 되며, 나머지 세 칸은 그대로입니다.
            이미 답한 질문에 같은 답이 다시 오면 아무것도 바꾸지 않고 지금 카드를 200 으로 돌려주고, 다른 답이면 409 INVALID_STATE 입니다.
            확인 순서는 질문 번호(404), 초안을 만드는 중인 카드(409 INVALID_STATE), 이미 한 답(200·409), 확정된 카드(409 CARD_CONFIRMED), 답의 모양(400) 입니다.
            답의 글은 1,000자까지이고 앞뒤 공백도 그대로 저장합니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "답을 반영한 카드, 이미 한 답과 같은 답이 다시 왔으면 그대로인 카드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 질문 번호가 정수가 아님, 본문을 읽을 수 없음, source 가 USER_STATED 가 아님, text 가 없거나 공백뿐이거나 1,000자를 넘거나 U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 본인 카드 없음 또는 그 번호의 질문 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "CARD_CONFIRMED: 확정된 카드는 다시 열어야 답할 수 있음, INVALID_STATE: 이미 다른 답을 한 질문 또는 초안을 만드는 중인 카드",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<CardResponse> answer(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "카드 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @Parameter(description = "질문 번호(되묻기 기록의 turnNo)")
            @PathVariable int turnNo,
            @RequestBody InterviewAnswerRequest request) {

        return ApiResponse.ok(CardResponse.from(
                interviewAnswer.answer(toUuid(id), loginUser.id(), turnNo, request.text(), request.source())));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CardNotFoundException();
        }

    }
}
