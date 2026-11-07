package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.GeneralConditionType;
import com.gitory.backend.matching.domain.MatchCondition;
import com.gitory.backend.matching.domain.MatchConditionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MatchConditionResponse(String id, CriterionType type, String text, String quote,
                                     @Schema(description = "학력·병역·어학·자격증·지역·기타 같은 일반 조건의 종류. 기술·경험 조건과 담당 업무는 null",
                                             nullable = true) GeneralConditionType generalType,
                                     @Schema(description = "기술·경험 조건은 MET·NOT_MET·UNJUDGED(아직 판단 안 함), 학력은 MET·NOT_MET·UNKNOWN(인적사항 없음)·CHECK(수준을 못 읽었거나 예외가 적힘), 병역·어학·자격증·지역·기타는 CHECK, 담당 업무는 null",
                                             nullable = true) MatchConditionStatus status,
                                     @Schema(description = "이 조건을 채웠다고 AI 가 판단한 확정 카드. 기술·경험 조건이 MET 일 때만 있다") List<MatchMetByResponse> metBy) {

    public static MatchConditionResponse from(MatchCondition condition) {

        return new MatchConditionResponse(condition.id().toString(), condition.type(), condition.text(),
                condition.quote(), condition.generalType(), condition.status(),
                condition.metBy().stream().map(MatchMetByResponse::from).toList());

    }
}
