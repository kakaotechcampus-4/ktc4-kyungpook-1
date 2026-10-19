package com.gitory.backend.card.domain;

import java.time.Instant;

public record VersionContent(VersionSource source, Instant savedAt, VersionSlots slots) {
}
