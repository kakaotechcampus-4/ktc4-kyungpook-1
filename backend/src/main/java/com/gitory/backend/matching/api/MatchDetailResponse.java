package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.MatchDetail;
import com.gitory.backend.matching.domain.MatchGrade;
import com.gitory.backend.matching.domain.MatchTarget;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MatchDetailResponse(String id, String company,
                                  @Schema(description = "같은 회사의 공고를 묶는 id. 회사 이름 표기가 달라도 같다") String companyId,
                                  @Schema(description = "회사 로고 https 주소. 없으면 null 이라 화면이 이니셜을 보인다", nullable = true) String logoUrl,
                                  String role, JobCategory category, MatchGrade fit,
                                  MatchCountResponse required, MatchCountResponse preferred,
                                  MatchDetailExperienceResponse experience,
                                  @Schema(description = "목록과 같다: 필수·우대 조건의 기술 이름을 조건 순서대로 한 번씩. 없으면 []") List<String> techs,
                                  @Schema(description = "목록과 같다: 수준을 읽은 필수 학력 중 가장 낮은 수준. 없으면 null", nullable = true) MatchEducationResponse education,
                                  MatchSourceResponse source,
                                  @Schema(description = "아직 판단하지 않은 기술·경험 조건이 있거나 카드가 바뀌어 다시 판단할 짝이 남았으면 true. 화면은 '이 공고와 맞춰 보기' 를 보인다") boolean needsJudgement,
                                  @Schema(description = "needsJudgement 면 null", nullable = true) MatchSummaryResponse summary,
                                  List<MatchConditionResponse> conditions) {

    public static MatchDetailResponse from(MatchDetail detail) {

        MatchTarget target = detail.target();

        return new MatchDetailResponse(target.id().toString(), target.company(), target.companyId(), target.logoUrl(),
                target.role(), target.category(),
                target.fit(), MatchCountResponse.from(target.required()), MatchCountResponse.from(target.preferred()),
                new MatchDetailExperienceResponse(target.experienceLevel(), target.experienceMinYears(),
                        detail.experienceQuote(), detail.experienceStatus()),
                target.techs(), MatchEducationResponse.from(target.education()),
                new MatchSourceResponse(target.sourceUrl(), target.collectedAt(), target.expiresAt()),
                detail.needsJudgement(), detail.summary() == null ? null : MatchSummaryResponse.from(detail.summary()),
                detail.conditions().stream().map(MatchConditionResponse::from).toList());

    }
}
