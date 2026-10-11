package com.gitory.backend.card.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SavedDraft(UUID cardId, short versionNo, Map<StarField, String> texts, Instant savedAt) {
}
