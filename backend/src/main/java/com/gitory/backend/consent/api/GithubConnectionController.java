package com.gitory.backend.consent.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.GithubDisconnectService;
import com.gitory.backend.consent.domain.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "GitHub", description = "GitHub 연결 관리")
public class GithubConnectionController {

    private final GithubDisconnectService githubDisconnect;

    @PostMapping("/api/github/disconnect")
    @Operation(summary = "GitHub 연결 해제", description = "저장된 토큰을 즉시 지우고 GitHub 쪽 앱 권한도 지웁니다. 이미 해제된 상태여도 ok 입니다. 변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제됨(이미 해제된 경우 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<DisconnectResponse> disconnect(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        githubDisconnect.disconnect(loginUser.id());

        return ApiResponse.ok(new DisconnectResponse(true));

    }
}
