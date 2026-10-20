package com.gitory.backend.matching.domain;

import java.time.Instant;

public record FetchedPosting(String externalId, String title, String sourceUrl, String location,
                             Instant sourceUpdatedAt, Instant expiresAt) {
}
