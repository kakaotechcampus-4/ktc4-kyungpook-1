package com.gitory.backend.profile.api;

import com.gitory.backend.profile.domain.InterestCategory;
import com.gitory.backend.profile.domain.ProfileView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "프로필. 관심 분야와 메일 수신 설정만 담습니다.")
public record ProfileResponse(
        @Schema(description = "관심 분야 직무 분류 이름, 3개까지. 아직 고르지 않았으면 []") List<InterestCategory> interests,
        @Schema(description = "메일 알림을 받을 주소. 적지 않았으면 null", nullable = true) String email,
        @Schema(description = "메일 알림 수신 여부. 한 번도 저장하지 않았으면 false") boolean mailOptIn,
        @Schema(description = "마지막으로 저장한 시각. 한 번도 저장하지 않았으면 null 이라, 화면은 처음 입력을 띄웁니다", nullable = true)
        Instant savedAt) {

    static ProfileResponse from(ProfileView profile) {

        return new ProfileResponse(profile.interests(), profile.email(), profile.mailOptIn(), profile.savedAt());

    }
}
