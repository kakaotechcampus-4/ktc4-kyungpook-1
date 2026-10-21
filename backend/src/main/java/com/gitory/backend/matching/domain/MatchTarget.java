package com.gitory.backend.matching.domain;

import java.time.Instant;
import java.util.UUID;

public record MatchTarget(UUID id, String company, String role, JobCategory category, MatchGrade fit,
                          MatchCount required, MatchCount preferred, ExperienceLevel experienceLevel,
                          Integer experienceMinYears, String sourceUrl, Instant collectedAt, Instant expiresAt) {
}
