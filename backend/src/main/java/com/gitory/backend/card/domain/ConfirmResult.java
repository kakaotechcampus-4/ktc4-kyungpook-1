package com.gitory.backend.card.domain;

import java.util.List;
import java.util.UUID;

public record ConfirmResult(
        UUID cardId,
        CardStatus status,
        short versionNo,
        List<UUID> usedCandidateIds,
        int remainingCandidates,
        UUID repoId) {
}
