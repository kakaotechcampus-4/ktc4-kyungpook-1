package com.gitory.backend.agent.port;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssistantStepRequest(List<Message> messages, List<DoneStep> steps, Context context, int stepsLeft) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Message(int seq, String text) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Find(String company, List<String> keywords) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Posting(String ref, String company, String title, Integer daysLeft, Integer requiredMet,
                          Integer requiredTotal) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Match(int requiredMet, int requiredTotal, List<String> met, List<String> missing) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DoneStep(int seq, AssistantTool tool, boolean ok, String error, Find find, String postingRef,
                           String repoRef, List<Posting> postings, Integer totalFound, Match match,
                           Integer questionCount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Repo(String ref, String name, boolean analyzed, boolean analyzing) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Context(int confirmedCards, List<Repo> repos, List<String> interests) {
    }
}
