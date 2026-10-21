package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.MatchGrade;
import com.gitory.backend.matching.domain.MatchTarget;

public record MatchTargetResponse(String id, String company, String role, JobCategory category, MatchGrade fit,
                                  MatchCountResponse required, MatchCountResponse preferred,
                                  MatchExperienceResponse experience, MatchSourceResponse source) {

    public static MatchTargetResponse from(MatchTarget target) {

        return new MatchTargetResponse(target.id().toString(), target.company(), target.role(), target.category(),
                target.fit(), MatchCountResponse.from(target.required()), MatchCountResponse.from(target.preferred()),
                new MatchExperienceResponse(target.experienceLevel(), target.experienceMinYears()),
                new MatchSourceResponse(target.sourceUrl(), target.collectedAt(), target.expiresAt()));

    }
}
