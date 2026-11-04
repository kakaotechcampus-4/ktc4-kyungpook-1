package com.gitory.backend.ingest.domain;

public record ReadmeRefresh(boolean needsAiSummary, String ownerLogin, String name, String language,
                            String readmeHead) {
}
