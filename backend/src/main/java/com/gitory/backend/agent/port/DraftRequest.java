package com.gitory.backend.agent.port;

import java.util.List;

public record DraftRequest(
        String analysisTargetLogin,
        List<String> evidenceSummaries,
        List<String> churnLines,
        List<String> dependencyFiles,
        List<String> answers
) {
}
