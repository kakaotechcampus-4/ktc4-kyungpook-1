package com.gitory.backend.assistant.domain;

import java.util.List;
import java.util.UUID;

public record ToolResult(ToolError error, List<RunItem.Posting> postings, Integer totalFound, RunItem.Posting posting,
                         RunItem.Match match, Integer questionCount, UUID coverLetterId, UUID repoId, UUID jobId) {

    public static ToolResult failed(ToolError error) {

        return new ToolResult(error, null, null, null, null, null, null, null, null);

    }

    public static ToolResult found(List<RunItem.Posting> postings, int totalFound) {

        return new ToolResult(null, postings, totalFound, null, null, null, null, null, null);

    }

    public static ToolResult matched(RunItem.Posting posting, RunItem.Match match) {

        return new ToolResult(null, null, null, posting, match, null, null, null, null);

    }

    public static ToolResult coverLetter(int questionCount, UUID coverLetterId) {

        return new ToolResult(null, null, null, null, null, questionCount, coverLetterId, null, null);

    }

    public static ToolResult repoAnalysis(UUID repoId, UUID jobId) {

        return new ToolResult(null, null, null, null, null, null, null, repoId, jobId);

    }

    public boolean ok() {

        return error == null;

    }
}
