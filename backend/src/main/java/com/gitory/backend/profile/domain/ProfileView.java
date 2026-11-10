package com.gitory.backend.profile.domain;

import java.time.Instant;
import java.util.List;

/** 한 번도 저장하지 않았으면 savedAt 이 null 이라, 화면은 이것으로 처음 입력을 띄울지 정한다 */
public record ProfileView(List<InterestCategory> interests, String email, boolean mailOptIn, Instant savedAt) {

    static ProfileView empty() {

        return new ProfileView(List.of(), null, false, null);

    }
}
