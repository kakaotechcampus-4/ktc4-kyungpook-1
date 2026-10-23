package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.ExperienceLevel;

public record MatchExperienceResponse(ExperienceLevel level, Integer minYears) {
}
