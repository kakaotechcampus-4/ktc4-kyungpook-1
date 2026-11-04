package com.gitory.backend.coverletter.domain;

import java.time.Instant;
import java.util.List;

public record QuestionPolish(String summary, List<PolishPoint> points, PolishedDraft draft, Instant createdAt) {
}
