package com.gitory.backend.card.domain;

import java.util.UUID;

public record CardGeneration(UUID jobId, boolean partial) {
}
