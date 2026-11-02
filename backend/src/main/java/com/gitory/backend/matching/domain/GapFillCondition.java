package com.gitory.backend.matching.domain;

import java.util.List;
import java.util.UUID;

public record GapFillCondition(UUID conditionId, GapFillDecision decision, String decisionReason, boolean forced,
                               List<GapFillStep> steps, GapFillProposal proposal, GapFillResult result) {

    public static GapFillCondition waiting(UUID conditionId) {

        return new GapFillCondition(conditionId, null, null, false, List.of(), null, null);

    }

    public GapFillCondition withProposal(GapFillProposal proposal) {

        return new GapFillCondition(conditionId, decision, decisionReason, forced, steps, proposal, result);

    }
}
