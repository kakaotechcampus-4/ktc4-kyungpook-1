package com.gitory.backend.coverletter.domain;

import java.time.Instant;
import java.util.UUID;

public record CoverLetterSummary(UUID id, CoverLetterStage stage, String jobRole, int questionCount,
                                 String firstQuestion, Instant createdAt, Instant updatedAt) {
}
