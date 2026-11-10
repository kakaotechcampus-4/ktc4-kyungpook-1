package com.gitory.backend.profile.api;

import com.gitory.backend.profile.domain.ProfileInput;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "프로필을 통째로 보냅니다. 옛 학력·경력·어학·자격증 칸을 같이 보내도 무시합니다.")
public record ProfileRequest(
        @Schema(description = "관심 분야 — 공고 직무 분류 이름(BACKEND · FRONTEND · MOBILE · DEVOPS · DATA · AI_ML · SECURITY · QA · ETC) 3개까지, 겹치지 않게. 안 보내면 저장된 값을 그대로 둡니다", nullable = true)
        List<String> interests,
        @Schema(description = "새 공고 알림을 받을 메일 주소 — 254자까지, 공백 없이 @ 하나와 그 뒤의 점이 있어야 합니다. 빈 글이면 주소를 지우고, 안 보내면 저장된 값을 그대로 둡니다", nullable = true)
        String email,
        @Schema(description = "메일 알림 수신 여부. true 이면 주소가 있어야 합니다. 안 보내면 저장된 값을 그대로 둡니다", nullable = true)
        Boolean mailOptIn) {

    ProfileInput toInput() {

        return new ProfileInput(interests, email, mailOptIn);

    }
}
