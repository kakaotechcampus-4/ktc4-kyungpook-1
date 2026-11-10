package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterAppendRequest(long coverLetterId, String jobRole, String memo,
                                       List<String> strengths,
                                       @JsonInclude(JsonInclude.Include.NON_NULL) CoverLetterPostingInput posting,
                                       List<CoverLetterPolishRequest.Other> others,
                                       CoverLetterPolishRequest.Target target, long addedCardId) {
}
