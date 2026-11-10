package com.gitory.backend.assistant.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitory.backend.agent.port.AssistantTool;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunItem(int seq, Kind kind, String text, AssistantTool tool, Boolean ok, String error, Find find,
                      String postingRef, String repoRef, List<Posting> postings, Integer totalFound, Posting posting,
                      Match match, Integer questionCount, UUID coverLetterId, UUID jobId, UUID repoId,
                      String repoName, String askKind, List<String> options, String cardRef, UUID cardId,
                      String cardTitle, String letterRef, UUID letterId, Integer gapsFilled, Question question,
                      List<Polished> polished, Integer remainingQuestions) {

    public enum Kind {
        USER,
        STEP,
        ASK,
        SAY
    }

    public record Find(String company, List<String> keywords) {

        public Find {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record Posting(String ref, UUID postingId, String company, String title, Integer daysLeft,
                          Integer requiredMet, Integer requiredTotal) {

        public Posting withRef(String ref) {

            return new Posting(ref, postingId, company, title, daysLeft, requiredMet, requiredTotal);

        }

        public Posting withRequired(int met, int total) {

            return new Posting(ref, postingId, company, title, daysLeft, met, total);

        }
    }

    public record Match(int requiredMet, int requiredTotal, List<String> met, List<String> missing) {

        public Match {
            met = met == null ? List.of() : List.copyOf(met);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    public record Question(String field, String text) {
    }

    public record Polished(int seq, int points) {
    }

    static RunItem user(int seq, String text) {

        return plain(seq, Kind.USER, text, null, null);

    }

    static RunItem ask(int seq, String text, String askKind, List<String> options) {

        return plain(seq, Kind.ASK, text, askKind, options);

    }

    static RunItem say(int seq, String text) {

        return plain(seq, Kind.SAY, text, null, null);

    }

    static RunItem started(int seq, ToolCall call) {

        AssistantTool tool = call.tool();

        return switch (tool) {
            case FIND_POSTINGS -> step(seq, tool, call.find(), null, null, null, null, null, null, null);
            case MATCH_POSTING, FILL_POSTING_GAPS -> step(seq, tool, null, call.postingRef(), null, call.posting(),
                    null, null, null, null);
            case START_COVER_LETTER -> step(seq, tool, null, call.postingRef(), null, null, null, null, null, null);
            case START_REPO_ANALYSIS -> step(seq, tool, null, null, call.repoRef(), null,
                    call.repo() == null ? null : call.repo().name(), null, null, null);
            case ASK_CARD_GAP -> step(seq, tool, null, null, null, null, null, call.cardRef(),
                    call.card() == null ? null : call.card().title(), null);
            case POLISH_COVER_LETTER -> step(seq, tool, null, null, null, null, null, null, null, call.letterRef());
        };

    }

    RunItem finished(ToolResult result, List<Posting> labeledPostings) {

        return new RunItem(seq, kind, text, tool, result.ok(), result.error() == null ? null : result.error().name(),
                find, postingRef, repoRef, labeledPostings, result.totalFound(),
                result.posting() == null ? posting : result.posting(), result.match(), result.questionCount(),
                result.coverLetterId(), result.jobId(), result.repoId(), repoName, askKind, options, cardRef,
                result.cardId(), result.cardTitle() == null ? cardTitle : result.cardTitle(), letterRef,
                result.letterId(), result.gapsFilled(), result.question(), result.polished(),
                result.remainingQuestions());

    }

    private static RunItem plain(int seq, Kind kind, String text, String askKind, List<String> options) {

        return new RunItem(seq, kind, text, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, askKind, options, null, null, null, null, null, null, null, null, null);

    }

    private static RunItem step(int seq, AssistantTool tool, Find find, String postingRef, String repoRef,
                                Posting posting, String repoName, String cardRef, String cardTitle,
                                String letterRef) {

        return new RunItem(seq, Kind.STEP, null, tool, null, null, find, postingRef, repoRef, null, null, posting,
                null, null, null, null, null, repoName, null, null, cardRef, null, cardTitle, letterRef, null, null,
                null, null, null);

    }
}
