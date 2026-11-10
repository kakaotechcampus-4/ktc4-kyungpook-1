package com.gitory.backend.assistant.domain;

import com.gitory.backend.card.domain.StarField;

import java.util.List;
import java.util.UUID;

public record CardLabel(String ref, UUID cardId, String title, List<StarField> missing) {
}
