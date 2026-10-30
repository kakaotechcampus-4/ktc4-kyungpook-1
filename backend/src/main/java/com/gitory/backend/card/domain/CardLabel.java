package com.gitory.backend.card.domain;

import java.util.UUID;

public record CardLabel(Long id, UUID publicId, String title, CardKind kind) {
}
