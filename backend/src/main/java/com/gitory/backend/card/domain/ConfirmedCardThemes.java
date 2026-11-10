package com.gitory.backend.card.domain;

import java.util.List;
import java.util.UUID;

public record ConfirmedCardThemes(Long cardId, UUID publicId, Long userId, String title, List<CardTheme> themes) {
}
