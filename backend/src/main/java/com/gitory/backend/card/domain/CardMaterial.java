package com.gitory.backend.card.domain;

import java.util.List;
import java.util.UUID;

public record CardMaterial(Long id, UUID publicId, String title, CardKind kind, String period, String sourceName,
                           List<CardMaterialStatement> statements) {
}
