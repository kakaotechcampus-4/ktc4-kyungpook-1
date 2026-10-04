package com.gitory.backend.consent.api;

import com.gitory.backend.common.api.ApiResponse;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 사용자 정보를 돌려준다.
 * 남의 id 로 조회하지 못하도록 id 는 경로가 아닌 세션에서 꺼낸다. */
@RestController
@Tag(name = "Session", description = "GitHub 로그인 세션의 사용자 정보")
public class MeController {

    private final MeQueryService meQuery;

    public MeController(MeQueryService meQuery) {
        this.meQuery = meQuery;
    }

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
}
