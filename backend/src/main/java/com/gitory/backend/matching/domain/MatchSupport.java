package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.StarField;

import java.util.UUID;

public record MatchSupport(UUID cardId, String cardTitle, CardKind cardKind, StarField field, String sentence) {
}
