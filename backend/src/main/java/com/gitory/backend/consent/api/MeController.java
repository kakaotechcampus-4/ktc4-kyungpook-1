package com.gitory.backend.consent.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.common.api.ErrorCode;
import com.gitory.backend.consent.domain.AccountDeletionService;
import com.gitory.backend.consent.domain.ActiveWorkRunningException;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.domain.MeQueryService;
import com.gitory.backend.consent.domain.MeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 사용자 정보를 돌려주고 탈퇴를 받는다.
 * 남의 id 로 조회하거나 지우지 못하도록 id 는 경로가 아닌 세션에서 꺼낸다. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Session", description = "GitHub 로그인 세션의 사용자 정보")
public class MeController {

    private final MeQueryService meQuery;
    private final AccountDeletionService accountDeletion;

    @GetMapping("/api/me")
    @Operation(summary = "내 정보 조회", description = "사용자 ID는 요청으로 받지 않고 로그인 세션에서 확인합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "현재 사용자·GitHub 연결·통계"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class),
                            examples = @ExampleObject(value = "{\"data\":null,\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")))
    })
    public ApiResponse<MeView> me(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        return ApiResponse.ok(meQuery.load(loginUser.id(), loginUser.avatarUrl()));
    }

    @DeleteMapping("/api/me")
    @Operation(summary = "회원 탈퇴", description = "내 계정과 카드·프로필·자소서·GitHub 연결을 바로 지우고 로그인 세션을 모두 끝냅니다. 분석·카드 초안·도우미·자소서 AI 작업이 돌고 있으면 아무것도 지우지 않고 409 로 거절합니다. 변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제됨. data 는 null"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "진행 중인 작업이 있음(ACTIVE_WORK_RUNNING), 아무것도 지우지 않음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ResponseEntity<ApiResponse<Void>> leave(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
                                   @Parameter(hidden = true) Authentication authentication,
                                   @Parameter(hidden = true) HttpServletRequest request,
                                   @Parameter(hidden = true) HttpServletResponse response) {

        try {
            accountDeletion.delete(loginUser.id());
        } catch (ActiveWorkRunningException running) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail(ErrorCode.ACTIVE_WORK_RUNNING, "진행 중인 작업이 끝난 뒤에 탈퇴할 수 있습니다."));
        }

        new SecurityContextLogoutHandler().logout(request, response, authentication);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("SESSION", "")
                .path("/").maxAge(0).httpOnly(true).secure(true).sameSite("Lax").build().toString());

        return ResponseEntity.ok(ApiResponse.ok(null));

    }
}
