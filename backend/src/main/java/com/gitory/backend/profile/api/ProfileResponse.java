package com.gitory.backend.profile.api;

import com.gitory.backend.profile.domain.Career;
import com.gitory.backend.profile.domain.Certification;
import com.gitory.backend.profile.domain.Education;
import com.gitory.backend.profile.domain.InterestCategory;
import com.gitory.backend.profile.domain.LanguageScore;
import com.gitory.backend.profile.domain.ProfileView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "인적사항. 목록 칸 모양은 요청과 같고, 비운 칸은 null 입니다.")
public record ProfileResponse(
        List<Education> educations,
        List<Career> careers,
        List<LanguageScore> languages,
        List<Certification> certifications,
        @Schema(description = "관심 분야 직무 분류 이름, 3개까지. 아직 고르지 않았으면 []") List<InterestCategory> interests,
        @Schema(description = "마지막으로 저장한 시각. 한 번도 저장하지 않았으면 null 이라, 화면은 처음 입력을 띄웁니다", nullable = true)
        Instant savedAt) {

    static ProfileResponse from(ProfileView profile) {

        return new ProfileResponse(profile.educations(), profile.careers(), profile.languages(),
                profile.certifications(), profile.interests(), profile.savedAt());

    }
}
