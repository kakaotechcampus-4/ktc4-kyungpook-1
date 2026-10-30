package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterQuestionsRequest(long coverLetterId, String jobRole, String memo, List<Question> questions) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Question(int questionId, String text, int charLimit, String questionTheme, CardDetail primaryCard,
                           CardDetail supportingCard, List<KnownAnswer> knownAnswers) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CardDetail(long cardId, String title, String cardKind, String sourceName,
                             List<Statement> statements) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Statement(String starSlot, String text) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KnownAnswer(String item, String text, Long cardId) {
    }
}
