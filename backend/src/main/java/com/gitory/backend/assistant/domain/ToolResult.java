package com.gitory.backend.assistant.domain;

import java.util.List;
import java.util.UUID;

public record ToolResult(ToolError error, List<RunItem.Posting> postings, Integer totalFound, RunItem.Posting posting,
                         RunItem.Match match, Integer questionCount, UUID coverLetterId, UUID repoId, UUID jobId,
                         UUID cardId, String cardTitle, UUID letterId, Integer gapsFilled, RunItem.Question question,
                         List<RunItem.Polished> polished, Integer remainingQuestions) {

    public static ToolResult failed(ToolError error) {

        return new ToolResult(error, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);

    }

    public static ToolResult found(List<RunItem.Posting> postings, int totalFound) {

        return new ToolResult(null, postings, totalFound, null, null, null, null, null, null, null, null, null, null,
                null, null, null);

    }

    public static ToolResult matched(RunItem.Posting posting, RunItem.Match match) {

        return new ToolResult(null, null, null, posting, match, null, null, null, null, null, null, null, null, null,
                null, null);

    }

    public static ToolResult coverLetter(int questionCount, UUID coverLetterId) {

        return new ToolResult(null, null, null, null, null, questionCount, coverLetterId, null, null, null, null,
                null, null, null, null, null);

    }

    public static ToolResult repoAnalysis(UUID repoId, UUID jobId) {

        return new ToolResult(null, null, null, null, null, null, null, repoId, jobId, null, null, null, null, null,
                null, null);

    }

    public static ToolResult gapsFilled(RunItem.Posting posting, RunItem.Match match, int gapsFilled) {

        return new ToolResult(null, null, null, posting, match, null, null, null, null, null, null, null, gapsFilled,
                null, null, null);

    }

    /** 더 물을 것이 없으면 field 와 text 가 null 이고, 그래도 걸음은 성공이다 */
    public static ToolResult asked(UUID cardId, String cardTitle, String field, String text) {

        return new ToolResult(null, null, null, null, null, null, null, null, null, cardId, cardTitle, null, null,
                text == null ? null : new RunItem.Question(field, text), null, null);

    }

    public static ToolResult polished(UUID letterId, List<RunItem.Polished> polished, int remainingQuestions) {

        return new ToolResult(null, null, null, null, null, null, null, null, null, null, null, letterId, null, null,
                List.copyOf(polished), remainingQuestions);

    }

    public boolean ok() {

        return error == null;

    }
}
