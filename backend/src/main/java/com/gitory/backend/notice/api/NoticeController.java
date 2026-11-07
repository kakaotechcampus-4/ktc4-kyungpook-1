package com.gitory.backend.notice.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.notice.domain.NoticeNotFoundException;
import com.gitory.backend.notice.domain.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "서버가 남긴 알림 — 카드 초안이 끝났거나 레포 읽기가 실패했을 때")
public class NoticeController {

    private final NoticeService notices;

    @GetMapping("/api/notifications")
    @Operation(summary = "알림 목록", description = """
            내 알림을 최근에 남긴 것부터 30개까지 돌려줍니다. readAt 이 null 이면 아직 읽지 않은 알림입니다.
            kind 는 CARDS_DRAFTED(카드 초안이 끝남), REPO_REFRESHED(서버가 스스로 다시 읽어 카드 초안이 끝남), ANALYSIS_FAILED(레포 읽기가 실패함),
            POSTINGS_MATCHED(새로 올라온 공고 중 확정 카드와 잘 맞는 곳을 찾음)입니다.
            repoName·cardTitles·postings 는 알림을 남긴 때의 값이고, 해당하지 않는 값은 null 이며 cardTitles·postings 만 빈 배열입니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "알림 배열. 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<List<NoticeResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(notices.list(loginUser.id()).stream().map(NoticeResponse::from).toList());

    }

    @PostMapping("/api/notifications/{id}/read")
    @Operation(summary = "알림 읽음 표시", description = """
            알림 하나를 읽음으로 표시합니다. 이미 읽은 알림은 처음 읽은 시각을 그대로 두고 오류도 아닙니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "읽음으로 표시했음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND: UUID 가 아니거나 없는 알림이거나 남의 알림",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<NoticeOkResponse> read(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
            @Parameter(description = "알림 목록 응답의 id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable String id) {

        notices.read(toUuid(id), loginUser.id());

        return ApiResponse.ok(new NoticeOkResponse(true));

    }

    @PostMapping("/api/notifications/read-all")
    @Operation(summary = "알림 모두 읽음 표시", description = """
            내 읽지 않은 알림을 모두 읽음으로 표시합니다. 읽지 않은 알림이 없어도 오류가 아닙니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "읽음으로 표시했음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 세션 없음",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<NoticeOkResponse> readAll(
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        notices.readAll(loginUser.id());

        return ApiResponse.ok(new NoticeOkResponse(true));

    }

    private UUID toUuid(String id) {

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NoticeNotFoundException();
        }

    }
}
