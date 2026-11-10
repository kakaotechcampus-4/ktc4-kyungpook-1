package com.gitory.backend.feedback.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.feedback.domain.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "문제 신고")
public class FeedbackController {

    private final FeedbackService feedbacks;

    @PostMapping("/api/feedback")
    @Operation(summary = "문제 신고 보내기", description = """
            로그인한 사용자가 쓴 글과 화면 주소, 브라우저 종류를 우리 DB 에 저장합니다.
            팀 Discord 에는 신고가 왔다는 한 줄만 가고 사용자가 쓴 글은 밖으로 나가지 않습니다.
            하루 10건까지 받습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장됨. data 는 null"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "글이 비었거나 1000자 초과·화면 주소 255자 초과·깨진 JSON",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "하루 신고 한도 초과(RATE_LIMITED)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<Void> report(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @RequestBody FeedbackRequest request) {

        feedbacks.report(loginUser.id(), request.message(), request.path(), userAgent);

        return ApiResponse.ok(null);

    }
}
