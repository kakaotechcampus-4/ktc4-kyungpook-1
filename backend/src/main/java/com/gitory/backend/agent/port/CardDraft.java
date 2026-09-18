package com.gitory.backend.agent.port;

import java.util.List;
import java.util.Map;

public record CardDraft(
        String title,
        Map<String, String> starText,
        Map<String, List<String>> starEvidence,
        String sharedWith,
        List<String> removed
) {
}
