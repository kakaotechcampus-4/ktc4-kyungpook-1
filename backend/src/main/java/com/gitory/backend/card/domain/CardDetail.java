package com.gitory.backend.card.domain;

import com.gitory.backend.ingest.domain.RepositoryReference;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CardDetail(
        UUID publicId,
        CardKind kind,
        CardStatus status,
        String title,
        String period,
        RepositoryReference repository,
        VersionSource source,
        Instant versionSavedAt,
        VersionSlots slots,
        List<MaskRule> maskRules,
        Instant confirmedAt,
        Instant createdAt) {
}
