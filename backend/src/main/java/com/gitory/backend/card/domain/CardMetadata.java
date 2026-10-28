package com.gitory.backend.card.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CardMetadata(UUID cardId, String title, String period, List<CardTheme> themes, Instant updatedAt) {
}
