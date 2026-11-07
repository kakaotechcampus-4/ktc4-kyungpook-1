package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterDraftsRequest(long coverLetterId, String jobRole, String memo,
                                       List<String> strengths, List<Question> questions,
                                       @JsonInclude(JsonInclude.Include.NON_NULL) CoverLetterPostingInput posting) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Question(int questionId, String text, int charLimit, String questionTheme,
                           CoverLetterQuestionsRequest.CardDetail primaryCard,
                           CoverLetterQuestionsRequest.CardDetail supportingCard,
                           List<CoverLetterQuestionsRequest.KnownAnswer> answers, List<String> missingItems) {
    }
}
