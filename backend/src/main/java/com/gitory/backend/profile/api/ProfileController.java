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
@Tag(name = "Profile", description = "관심 분야·메일 수신 프로필")
public class ProfileController {

    private final ProfileService profiles;

    @GetMapping("/api/profile")
    @Operation(summary = "내 프로필", description = "한 번도 저장하지 않았으면 관심 분야가 비고 savedAt 이 null 입니다. 화면은 이것으로 처음 입력을 띄울지 정합니다.")
    public ApiResponse<ProfileResponse> get(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {

        return ApiResponse.ok(ProfileResponse.from(profiles.of(loginUser.id())));

    }

    @PutMapping("/api/profile")
    @Operation(summary = "내 프로필 저장", description = """
            관심 분야(interests)는 보내면 통째로 바꾸고, 안 보내면 저장된 값을 그대로 둡니다. 옛 학력·경력·어학·자격증 칸은 보내도 무시합니다.
            처음 입력을 건너뛰면 빈 관심 분야([])를 보내 저장해, 다음부터 처음 입력을 띄우지 않게 합니다.
            메일 주소(email)와 수신 여부(mailOptIn)도 안 보내면 저장된 값을 그대로 두며, 수신을 새로 켜거나 켠 채 주소를 바꾸면 그 주소로 확인 메일을 한 통 보냅니다.
            변경 요청이므로 세션과 CSRF 헤더가 모두 필요합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장한 프로필"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 관심 분야가 4개 이상이거나 겹치거나 모르는 값임, 메일 주소가 254자를 넘거나 모양이 틀림, 수신을 켰는데 저장될 주소가 없음. 메시지에 몇 번째 칸인지 적습니다",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<ProfileResponse> replace(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser,
                                                @RequestBody ProfileRequest request) {

        return ApiResponse.ok(ProfileResponse.from(profiles.replace(loginUser.id(), request.toInput())));

    }
}
