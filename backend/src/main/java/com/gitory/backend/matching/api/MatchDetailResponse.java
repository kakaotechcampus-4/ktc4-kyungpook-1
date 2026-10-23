package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.MatchDetail;
import com.gitory.backend.matching.domain.MatchGrade;
import com.gitory.backend.matching.domain.MatchTarget;

import java.util.List;

public record MatchDetailResponse(String id, String company, String role, JobCategory category, MatchGrade fit,
                                  MatchCountResponse required, MatchCountResponse preferred,
                                  MatchDetailExperienceResponse experience, MatchSourceResponse source,
                                  List<MatchConditionResponse> conditions) {

    public static MatchDetailResponse from(MatchDetail detail) {

        MatchTarget target = detail.target();

        return new MatchDetailResponse(target.id().toString(), target.company(), target.role(), target.category(),
                target.fit(), MatchCountResponse.from(target.required()), MatchCountResponse.from(target.preferred()),
                new MatchDetailExperienceResponse(target.experienceLevel(), target.experienceMinYears(),
                        detail.experienceQuote()),
                new MatchSourceResponse(target.sourceUrl(), target.collectedAt(), target.expiresAt()),
                detail.conditions().stream().map(MatchConditionResponse::from).toList());

    }
}
