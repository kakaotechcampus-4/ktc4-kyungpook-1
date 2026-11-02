package com.gitory.backend.profile.domain;

import java.time.Instant;
import java.util.List;

/** 한 번도 저장하지 않았으면 네 목록이 비고 savedAt 이 null 이라, 화면은 이것으로 처음 입력을 띄울지 정한다 */
public record ProfileView(List<Education> educations, List<Career> careers, List<LanguageScore> languages,
                          List<Certification> certifications, List<InterestCategory> interests, Instant savedAt) {

    static ProfileView empty() {

        return new ProfileView(List.of(), List.of(), List.of(), List.of(), List.of(), null);

    }
}
