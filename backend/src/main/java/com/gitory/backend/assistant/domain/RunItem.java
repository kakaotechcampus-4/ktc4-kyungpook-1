package com.gitory.backend.assistant.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitory.backend.agent.port.AssistantTool;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunItem(int seq, Kind kind, String text, AssistantTool tool, Boolean ok, String error, Find find,
                      String postingRef, String repoRef, List<Posting> postings, Integer totalFound, Posting posting,
                      Match match, Integer questionCount, UUID coverLetterId, UUID jobId, UUID repoId,
                      String repoName, String askKind, List<String> options) {

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

    static RunItem user(int seq, String text) {

        return new RunItem(seq, Kind.USER, text,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null);

    }

    static RunItem ask(int seq, String text, String askKind, List<String> options) {

        return new RunItem(seq, Kind.ASK, text,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                askKind, options);

    }

    static RunItem say(int seq, String text) {

        return new RunItem(seq, Kind.SAY, text,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null);

    }

    static RunItem started(int seq, ToolCall call) {

        return switch (call.tool()) {
            case FIND_POSTINGS -> step(seq, call.tool(), call.find(), null, null, null, null);
            case MATCH_POSTING -> step(seq, call.tool(), null, call.postingRef(), null, call.posting(), null);
            case START_COVER_LETTER -> step(seq, call.tool(), null, call.postingRef(), null, null, null);
            case START_REPO_ANALYSIS -> step(seq, call.tool(), null, null, call.repoRef(), null,
                    call.repo() == null ? null : call.repo().name());
        };

    }

    RunItem finished(ToolResult result, List<Posting> labeledPostings) {

        return new RunItem(seq, kind, text, tool, result.ok(), result.error() == null ? null : result.error().name(),
                find, postingRef, repoRef, labeledPostings, result.totalFound(),
                result.posting() == null ? posting : result.posting(), result.match(), result.questionCount(),
                result.coverLetterId(), result.jobId(), result.repoId(), repoName, askKind, options);

    }

    private static RunItem step(int seq, AssistantTool tool, Find find, String postingRef, String repoRef,
                                Posting posting, String repoName) {

        return new RunItem(seq, Kind.STEP, null, tool, null, null, find, postingRef, repoRef, null, null, posting,
                null, null, null, null, null, repoName, null, null);

    }
}
