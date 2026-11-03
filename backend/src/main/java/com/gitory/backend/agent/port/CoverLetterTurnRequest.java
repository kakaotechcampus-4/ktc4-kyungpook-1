package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterTurnRequest(long coverLetterId, String jobRole, String memo,
                                     List<String> strengths,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) CoverLetterPostingInput posting,
                                     Question question, List<CoverLetterPolishRequest.Other> others,
                                     List<Turn> thread, String trigger) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Question(int questionId, String text, int charLimit, String questionTheme,
                           CoverLetterQuestionsRequest.CardDetail primaryCard,
                           CoverLetterQuestionsRequest.CardDetail supportingCard,
                           List<CoverLetterQuestionsRequest.KnownAnswer> answers, String currentText,
                           List<String> blanks, List<String> missingEvidence) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Turn(int seq, String role, int questionId, String text, String item, String status) {
    }
}
