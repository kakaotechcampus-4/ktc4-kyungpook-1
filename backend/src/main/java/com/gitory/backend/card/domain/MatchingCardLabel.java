package com.gitory.backend.card.domain;

import java.util.UUID;

public record MatchingCardLabel(UUID publicId, String title, CardKind kind, Long userRepositoryId) {
}
