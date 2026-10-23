package com.gitory.backend.matching.api;

import java.time.Instant;

public record MatchSourceResponse(String url, Instant collectedAt, Instant expiresAt) {
}
