package com.gitory.backend.card.domain;

import java.time.Instant;
import java.util.UUID;

public record CardMetadata(UUID cardId, String title, String period, Instant updatedAt) {
}
