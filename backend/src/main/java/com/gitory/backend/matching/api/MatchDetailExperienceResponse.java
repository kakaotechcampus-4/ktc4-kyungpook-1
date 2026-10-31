package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.MatchConditionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record MatchDetailExperienceResponse(ExperienceLevel level, Integer minYears, String quote,
                                            @Schema(description = "인적사항 경력과 견준 결과로 MET·NOT_MET·UNKNOWN(인적사항 없음), 연수 요구가 없으면 null",
                                                    nullable = true) MatchConditionStatus status) {
}
