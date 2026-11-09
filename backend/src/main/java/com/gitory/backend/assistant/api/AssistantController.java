package com.gitory.backend.assistant.api;

import com.gitory.backend.assistant.domain.AssistantRunNotFoundException;
import com.gitory.backend.assistant.domain.AssistantRunService;
import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gitory.assistant.enabled", havingValue = "true")
@Tag(name = "Assistant", description = "홈 한 줄 명령 — 한 문장으로 맡기면 서버가 걸음마다 도구를 골라 실행한다")
public class AssistantController {

    private final AssistantRunService runs;

    @PostMapping("/api/assistant/runs")
    @Operation(summary = "홈 한 줄 명령 실행 만들기", description = """
            사용자가 적은 한 문장으로 실행을 만들고 RUNNING 으로 바로 돌려줍니다. 일은 배경에서 하며 첫 줄은 사용자의 말입니다.
            화면은 pollAfterMs 마다 GET 으로 다시 읽어 걸음이 쌓이는 것을 보여 줍니다.
            호출 제한은 실행 만들기와 말 더하기를 합쳐 셉니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "만든 실행"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 말이 앞뒤 공백을 뺀 1~500자가 아님, U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 1분 5번, 하루 50번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<AssistantRunResponse> start(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @RequestBody AssistantMessageRequest request) {

        return ApiResponse.ok(AssistantRunResponse.from(runs.start(loginUser.id(), loginUser.login(), request.text())));

    }

    @GetMapping("/api/assistant/runs/{id}")
    @Operation(summary = "홈 한 줄 명령 실행 조회", description = "지금까지 쌓인 줄 전체를 돌려줍니다. 도는 동안(RUNNING)에는 pollAfterMs 마다 다시 부릅니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND: 잘못된 UUID, 없는 실행, 남의 실행",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<AssistantRunResponse> get(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "실행 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {

        return ApiResponse.ok(AssistantRunResponse.from(runs.get(loginUser.id(), toUuid(id))));

    }

    @PostMapping("/api/assistant/runs/{id}/messages")
    @Operation(summary = "홈 한 줄 명령의 물음에 답하기", description = """
            물음을 기다리는 실행(WAITING_USER)에만 사용자의 말을 더하고 다시 RUNNING 으로 돌려줍니다.
            한 실행에 사용자의 말은 처음 말을 포함해 10개까지입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 말이 앞뒤 공백을 뺀 1~500자가 아님, U+0000 이 있음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND: 잘못된 UUID, 없는 실행, 남의 실행",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE: 물음을 기다리는 실행이 아님(도는 중이거나 끝남), 사용자의 말이 이미 10개",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED: 1분 5번, 하루 50번을 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<AssistantRunResponse> say(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "실행 id", schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @RequestBody AssistantMessageRequest request) {

        return ApiResponse.ok(AssistantRunResponse.from(runs.say(loginUser.id(), toUuid(id), request.text())));

    }

    private static UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new AssistantRunNotFoundException();
        }

    }
}
