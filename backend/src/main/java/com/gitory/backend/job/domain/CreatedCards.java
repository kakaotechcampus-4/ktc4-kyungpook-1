package com.gitory.backend.job.domain;

import java.util.List;
import java.util.UUID;

public record CreatedCards(UUID jobId, List<UUID> cardIds) {
}
