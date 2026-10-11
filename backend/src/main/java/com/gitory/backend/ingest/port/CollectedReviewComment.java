package com.gitory.backend.ingest.port;

import java.time.Instant;

public record CollectedReviewComment(long commentId, Long reviewId, String authorLogin, String bodyExcerpt,
                                     String path, Integer line, Instant commentedAt) {
}
