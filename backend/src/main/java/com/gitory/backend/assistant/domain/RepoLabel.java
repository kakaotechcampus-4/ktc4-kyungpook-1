package com.gitory.backend.assistant.domain;

import java.util.UUID;

public record RepoLabel(String ref, UUID id, String name) {
}
