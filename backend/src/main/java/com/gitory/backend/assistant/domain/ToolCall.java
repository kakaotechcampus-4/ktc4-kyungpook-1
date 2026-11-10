package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AssistantStepReply;
import com.gitory.backend.agent.port.AssistantTool;
import com.gitory.backend.coverletter.domain.NewQuestion;

import java.util.List;
import java.util.UUID;

public record ToolCall(UUID runId, AssistantTool tool, RunItem.Find find, String postingRef, RunItem.Posting posting,
                       String repoRef, RepoLabel repo, String jobRole, List<NewQuestion> questions, String cardRef,
                       CardLabel card, String letterRef, LetterLabel letter) {

    public static ToolCall of(AssistantRun run, AssistantStepReply reply) {

        return new ToolCall(run.getPublicId(), reply.tool(), findOf(reply), reply.postingRef(),
                run.postingOf(reply.postingRef()).orElse(null), reply.repoRef(),
                run.repoOf(reply.repoRef()).orElse(null), reply.jobRole(), questionsOf(reply), reply.cardRef(),
                run.cardOf(reply.cardRef()).orElse(null), reply.letterRef(),
                run.letterOf(reply.letterRef()).orElse(null));

    }

    /** 자소서는 공고 이름표를 주지 않으면 공고 없이 시작하고, 이름표를 줬는데 이 실행에 없으면 풀리지 않은 것이다 */
    public boolean resolvable() {

        return switch (tool) {
            case FIND_POSTINGS -> true;
            case MATCH_POSTING, FILL_POSTING_GAPS -> posting != null;
            case START_COVER_LETTER -> postingRef == null || posting != null;
            case START_REPO_ANALYSIS -> repo != null;
            case ASK_CARD_GAP -> card != null;
            case POLISH_COVER_LETTER -> letter != null;
        };

    }

    private static RunItem.Find findOf(AssistantStepReply reply) {

        if (reply.tool() != AssistantTool.FIND_POSTINGS) {
            return null;
        }
        if (reply.find() == null) {
            return new RunItem.Find(null, List.of());
        }

        return new RunItem.Find(reply.find().company(), reply.find().keywords());

    }

    private static List<NewQuestion> questionsOf(AssistantStepReply reply) {

        if (reply.questions() == null) {
            return List.of();
        }

        return reply.questions().stream()
                .map(question -> new NewQuestion(question.text(), question.charLimit()))
                .toList();

    }
}
