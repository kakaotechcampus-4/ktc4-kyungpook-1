package com.gitory.backend.coverletter.domain;

import java.time.Instant;
import java.util.UUID;

public record CoverLetterSummary(UUID id, CoverLetterStage stage, String jobRole, CoverLetterView.PostingRef posting,
                                 int questionCount, int emptyQuestionCount, String firstQuestion, Instant createdAt, Instant updatedAt) {
}
