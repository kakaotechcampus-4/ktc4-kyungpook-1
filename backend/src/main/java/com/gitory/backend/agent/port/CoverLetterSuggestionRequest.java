package com.gitory.backend.agent.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoverLetterSuggestionRequest(long coverLetterId, String jobRole, String memo, Question question,
                                           List<Card> cards, List<Candidate> candidates,
                                           @JsonInclude(JsonInclude.Include.NON_NULL) CoverLetterPostingInput posting) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Question(int questionId, String text, int charLimit, String questionTheme) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Card(long cardId, String title, String cardKind, String sourceName, String period, String summary) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Candidate(String candidateId, String title, String reason, String repoName,
                            List<String> commitMessages, int reviewCommentCount) {
    }
}
