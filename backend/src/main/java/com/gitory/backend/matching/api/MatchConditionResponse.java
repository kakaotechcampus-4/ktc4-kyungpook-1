package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.MatchCondition;

import java.util.List;

public record MatchConditionResponse(String id, CriterionType type, String text, String quote,
                                     List<MatchSupportResponse> supports) {

    public static MatchConditionResponse from(MatchCondition condition) {

        return new MatchConditionResponse(condition.id().toString(), condition.type(), condition.text(),
                condition.quote(), condition.supports().stream().map(MatchSupportResponse::from).toList());

    }
}
