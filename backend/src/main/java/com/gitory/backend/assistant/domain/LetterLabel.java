package com.gitory.backend.assistant.domain;

import java.util.UUID;

public record LetterLabel(String ref, UUID letterId, String jobRole, String company, int questionCount) {
}
