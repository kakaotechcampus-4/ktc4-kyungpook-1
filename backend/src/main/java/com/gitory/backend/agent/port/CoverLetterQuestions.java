package com.gitory.backend.agent.port;

import java.util.List;

public record CoverLetterQuestions(List<Question> questions) {

    public record Question(int questionId, List<String> requiredMissing, List<Ask> asks) {
    }

    public record Ask(String item, String question) {
    }
}
