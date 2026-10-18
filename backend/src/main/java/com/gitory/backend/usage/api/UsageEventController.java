package com.gitory.backend.usage.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.usage.domain.UsageEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Events", description = "화면 사용 지표")
public class UsageEventController {

    private final UsageEventService usageEvents;

    @PostMapping("/api/events")
    @Operation(summary = "사용 지표 기록", description = """
            로그인한 사용자의 사용 지표를 허용된 이름만 받아 저장합니다. sendBeacon 은 CSRF 헤더를
            붙일 수 없어 이 주소만 CSRF 검사에서 제외하고, 본문은 JSON 으로만 받습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "모르는 이름·name/at/path 없음·at 범위 밖·NUL 문자·props 2KB 초과나 값이 객체·배열·path 255자 초과·깨진 JSON·시각 모양이 아닌 at·객체가 아닌 props",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "JSON 이 아닌 본문",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<UsageEventAcceptedResponse> record(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @RequestBody UsageEventRequest request) {

        usageEvents.record(loginUser.id(), request.name(), request.props(), request.at(), request.path());

        return ApiResponse.ok(new UsageEventAcceptedResponse(true));

    }
}
