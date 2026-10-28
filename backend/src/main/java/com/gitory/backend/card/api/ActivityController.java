package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.ActivityNotFoundException;
import com.gitory.backend.card.domain.ActivityService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Activities", description = "레포 밖 경험(동아리·대외활동 등)을 묶는 활동")
public class ActivityController {

    private final ActivityService activities;

    @GetMapping("/api/activities")
    @Operation(summary = "내 활동 목록", description = "시작한 달이 최근인 순으로, 활동마다 안의 카드 수와 함께 돌려줍니다.")
    public ApiResponse<List<ActivityResponse>> list(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(activities.list(loginUser.id()).stream().map(ActivityResponse::from).toList());

    }

    @PostMapping("/api/activities")
    @Operation(summary = "활동 추가", description = """
            이름·종류·기간·역할로 활동을 만듭니다. 끝난 달이 없으면 진행 중입니다.
            같은 Idempotency-Key 로 다시 요청하면 새로 만들지 않고 그 키로 만든 활동을 돌려주며, 다시 보낸 값은 쓰지 않습니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새로 만든 활동 또는 같은 키로 이미 만든 활동"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 이름이 비었거나 50자를 넘음, 모르는 종류, 달이 YYYY-MM 이 아님, 끝난 달이 시작한 달보다 앞섬, 역할이 50자를 넘음, U+0000 이 있음, Idempotency-Key 가 255자를 넘음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<ActivityResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @RequestBody ActivityRequest request,
            @Parameter(description = "응답을 못 받고 다시 보내도 활동이 하나만 생기도록, 활동을 추가할 때마다 만들어 보내는 255자까지의 키")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        return ApiResponse.ok(ActivityResponse.from(activities.create(loginUser.id(), request.name(), request.type(),
                request.startMonth(), request.endMonth(), request.role(), idempotencyKey)));

    }

    @PatchMapping("/api/activities/{id}")
    @Operation(summary = "활동 수정", description = """
            보낸 값만 고칩니다. 키가 없거나 null 인 값은 그대로 두고, 끝난 달·역할은 빈 글이면 지웁니다(진행 중·역할 없음).
            아무 값도 보내지 않으면 400 이고, 고친 뒤에도 끝난 달이 시작한 달보다 앞서면 400 입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 뒤의 활동"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 바꿀 값이 없음, 또는 추가와 같은 입력 규칙 위반",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID 또는 본인 활동 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<ActivityResponse> edit(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "활동 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id,
            @RequestBody ActivityRequest request) {

        return ApiResponse.ok(ActivityResponse.from(activities.edit(toUuid(id), loginUser.id(), request.name(),
                request.type(), request.startMonth(), request.endMonth(), request.role())));

    }

    @DeleteMapping("/api/activities/{id}")
    @Operation(summary = "활동 삭제", description = """
            활동과 그 안의 카드를 버전·문장·근거·되묻기까지 모두 지웁니다. 다른 활동·저장소의 카드는 그대로입니다.
            화면은 지우기 전에 cardCount 로 함께 지워질 카드 수를 보여 주고 확인을 받습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "지움"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "잘못된 UUID, 본인 활동 없음 또는 이미 지운 활동",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<ActivityDeleteResponse> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "활동 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        activities.delete(toUuid(id), loginUser.id());

        return ApiResponse.ok(new ActivityDeleteResponse(true));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ActivityNotFoundException();
        }

    }
}
