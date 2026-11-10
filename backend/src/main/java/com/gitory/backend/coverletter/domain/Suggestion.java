package com.gitory.backend.coverletter.domain;

import java.util.UUID;

public record Suggestion(SuggestionSource source, String id, String title, String angle, UUID repoId) {
}
