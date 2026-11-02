package com.gitory.backend.matching.domain;

import java.util.List;
import java.util.UUID;

public record GapFillRequest(String companyName, String jobTitle, List<Condition> conditions, List<Card> cards,
                             List<Candidate> candidates, List<Card> draftCards, List<Repo> repos,
                             String githubLogin, List<UUID> forceConditionIds) {

    public record Condition(UUID conditionId, CriterionType type, String text) {
    }

    public record Card(UUID cardId, String title, String situation, String task, String action, String result,
                       List<String> techs) {
    }

    public record Candidate(UUID candidateId, String repoName, String title, String reason,
                            List<String> commitMessages) {
    }

    public record Repo(UUID repoId, String fullName, boolean analyzed) {
    }
}
