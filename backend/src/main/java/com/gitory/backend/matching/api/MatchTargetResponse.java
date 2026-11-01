package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.MatchGrade;
import com.gitory.backend.matching.domain.MatchTarget;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MatchTargetResponse(String id, String company,
                                  @Schema(description = "같은 회사의 공고를 묶는 id. 회사 이름 표기가 달라도 같다") String companyId,
                                  @Schema(description = "회사 로고 https 주소. 없으면 null 이라 화면이 이니셜을 보인다", nullable = true) String logoUrl,
                                  String role, JobCategory category, MatchGrade fit,
                                  MatchCountResponse required, MatchCountResponse preferred,
                                  MatchExperienceResponse experience,
                                  @Schema(description = "필수·우대 조건의 기술 이름(카드 techs 와 같은 값)을 조건 순서대로 한 번씩. 없으면 []") List<String> techs,
                                  @Schema(description = "필수 학력 조건 중 가장 낮은 수준. exempt 는 '또는 그에 준하는 경력' 같은 예외가 적힌 학력이다. 수준을 읽은 필수 학력이 없으면 null", nullable = true) MatchEducationResponse education,
                                  MatchSourceResponse source) {

    public static MatchTargetResponse from(MatchTarget target) {

        return new MatchTargetResponse(target.id().toString(), target.company(), target.companyId(), target.logoUrl(),
                target.role(), target.category(),
                target.fit(), MatchCountResponse.from(target.required()), MatchCountResponse.from(target.preferred()),
                new MatchExperienceResponse(target.experienceLevel(), target.experienceMinYears()),
                target.techs(), MatchEducationResponse.from(target.education()),
                new MatchSourceResponse(target.sourceUrl(), target.collectedAt(), target.expiresAt()));

    }
}
