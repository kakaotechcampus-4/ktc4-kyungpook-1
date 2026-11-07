package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterPolishRequest(long coverLetterId, String jobRole, String memo,
                                       List<String> strengths,
                                       @JsonInclude(JsonInclude.Include.NON_NULL) CoverLetterPostingInput posting,
                                       List<Other> others, Target target) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Other(int questionId, String text, String currentText) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Target(int questionId, String text, int charLimit, String questionTheme,
                         CoverLetterQuestionsRequest.CardDetail primaryCard,
                         CoverLetterQuestionsRequest.CardDetail supportingCard,
                         List<CoverLetterQuestionsRequest.KnownAnswer> answers, String currentText,
                         List<Integer> inferredSeqs) {
    }
}
