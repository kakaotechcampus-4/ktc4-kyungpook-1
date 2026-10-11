package com.gitory.backend.ingest.port;

import com.gitory.backend.ingest.domain.ReviewState;

import java.time.Instant;
import java.util.List;

public record CollectedReview(long reviewId, ReviewState state, String authorLogin, String bodyExcerpt,
                              Instant submittedAt, List<CollectedReviewComment> comments) {
}
