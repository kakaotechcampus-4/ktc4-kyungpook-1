package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Objects;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InterviewMissingSlot(InterviewStarSlot starSlot, int statementSeq, String body,
                                   InterviewConfidence confidence, List<InterviewCommit> linkedCommits) {

    public InterviewMissingSlot {

        Objects.requireNonNull(confidence, "confidence 는 null 일 수 없다 (AI 가 400 으로 거절한다)");

    }
}
