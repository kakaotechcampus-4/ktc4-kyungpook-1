package com.gitory.backend.agent.port;

import java.util.List;

public record JudgedCondition(String conditionId, List<ConditionSupport> metBy) {

    public record ConditionSupport(String cardId, String reason) {
    }
}
