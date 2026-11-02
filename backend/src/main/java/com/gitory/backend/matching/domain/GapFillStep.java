package com.gitory.backend.matching.domain;

public record GapFillStep(GapFillStepKind kind, Integer count, String query, String repoName) {
}
