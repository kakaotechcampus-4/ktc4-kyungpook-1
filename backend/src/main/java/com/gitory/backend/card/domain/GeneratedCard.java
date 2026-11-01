package com.gitory.backend.card.domain;

import java.util.UUID;

public record GeneratedCard(Long id, UUID publicId, Long candidateId, String title) {
}
