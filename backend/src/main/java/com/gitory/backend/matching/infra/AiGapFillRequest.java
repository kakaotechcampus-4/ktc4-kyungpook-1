package com.gitory.backend.matching.infra;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitory.backend.matching.domain.GapFillRequest;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record AiGapFillRequest(String companyName, String jobTitle, List<Condition> conditions, List<Card> cards,
                        List<Candidate> candidates, List<Card> draftCards, List<Repo> repos, String githubLogin,
                        List<String> forceConditionIds) {

    static AiGapFillRequest of(GapFillRequest request) {

        return new AiGapFillRequest(request.companyName(), request.jobTitle(),
                request.conditions().stream()
                        .map(condition -> new Condition(condition.conditionId().toString(), condition.type().name(),
                                condition.text()))
                        .toList(),
                cards(request.cards()),
                request.candidates().stream()
                        .map(candidate -> new Candidate(candidate.candidateId().toString(), candidate.repoName(),
                                candidate.title(), candidate.reason(), candidate.commitMessages()))
                        .toList(),
                cards(request.draftCards()),
                request.repos().stream()
                        .map(repo -> new Repo(repo.repoId().toString(), repo.fullName(), repo.analyzed()))
                        .toList(),
                request.githubLogin(),
                request.forceConditionIds().stream().map(UUID::toString).toList());

    }

    private static List<Card> cards(List<GapFillRequest.Card> cards) {

        return cards.stream()
                .map(card -> new Card(card.cardId().toString(), card.title(), card.situation(), card.task(),
                        card.action(), card.result(), card.techs()))
                .toList();

    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Condition(String conditionId, String criterionType, String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Card(String cardId, String title, String situation, String task, String action, String result,
                List<String> techs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Candidate(String candidateId, String repoName, String title, String reason, List<String> commitMessages) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Repo(String repoId, String fullName, boolean analyzed) {
    }
}
