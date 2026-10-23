package com.gitory.backend.matching.domain;

import java.time.Instant;
import java.util.List;

public record FetchedPosting(String externalId, String title, String sourceUrl, String location,
                             Instant sourceUpdatedAt, Instant expiresAt, String text, List<String> imageUrls) {

    public FetchedPosting {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
    }

    public FetchedPosting(String externalId, String title, String sourceUrl, String location,
                          Instant sourceUpdatedAt, Instant expiresAt, String text) {
        this(externalId, title, sourceUrl, location, sourceUpdatedAt, expiresAt, text, List.of());
    }
}
