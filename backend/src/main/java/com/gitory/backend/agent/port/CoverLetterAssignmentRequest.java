package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterAssignmentRequest(long coverLetterId, String jobRole, String memo,
                                           List<String> strengths, List<Question> questions,
                                           List<Card> cards,
                                           @JsonInclude(JsonInclude.Include.NON_NULL) CoverLetterPostingInput posting) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Question(int questionId, String text, int charLimit) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Card(long cardId, String title, String cardKind, String sourceName, String period, String summary) {
    }
}
