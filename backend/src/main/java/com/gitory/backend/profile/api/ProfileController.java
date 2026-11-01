package com.gitory.backend.profile.api;

import com.gitory.backend.common.api.ApiResponse;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.profile.domain.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Profile", description = "학력·경력·어학·자격증 인적사항")
public class ProfileController {

    private final ProfileService profiles;

    @GetMapping("/api/profile")
    @Operation(summary = "내 인적사항", description = "한 번도 저장하지 않았으면 네 목록이 비고 savedAt 이 null 입니다. 화면은 이것으로 처음 입력을 띄울지 정합니다.")
    public ApiResponse<ProfileResponse> get(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(ProfileResponse.from(profiles.of(loginUser.id())));

    }

    @PutMapping("/api/profile")
    @Operation(summary = "내 인적사항 저장", description = """
            네 목록을 통째로 바꿉니다. 처음 입력을 건너뛰면 빈 목록 네 개를 보내 저장해, 다음부터 처음 입력을 띄우지 않게 합니다.
            글은 앞뒤 공백을 지우고, 빈 글은 null 로 저장합니다. 목록 순서는 보낸 그대로입니다.
            관심 분야(interests)는 보내면 통째로 바꾸고, 안 보내면 저장된 값을 그대로 둡니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장한 인적사항"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 목록이 빠짐, 개수를 넘음, 필수 칸이 비었음, 모르는 종류, 달이 YYYY-MM 이 아님, 끝난 달이 시작한 달보다 앞섬, 학점·만점이 맞지 않음, 글이 길거나 U+0000 이 있음, 관심 분야가 4개 이상이거나 겹치거나 모르는 값임. 메시지에 어느 목록의 몇 번째인지 적습니다",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<ProfileResponse> replace(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
                                                @RequestBody ProfileRequest request) {

        return ApiResponse.ok(ProfileResponse.from(profiles.replace(loginUser.id(), request.toInput())));

    }
}
