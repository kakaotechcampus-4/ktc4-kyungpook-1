package com.gitory.backend.card.domain;

import java.time.Instant;

public record VersionHistoryItem(
        short versionNo,
        VersionSource source,
        Instant savedAt,
        String summary,
        boolean current) {
}
