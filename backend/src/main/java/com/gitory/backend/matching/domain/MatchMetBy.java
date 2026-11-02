package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardKind;

import java.util.UUID;

public record MatchMetBy(UUID cardId, String cardTitle, CardKind cardKind, UUID repoId, String reason) {
}
