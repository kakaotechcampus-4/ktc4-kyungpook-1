package com.gitory.backend.matching.api;

import com.gitory.backend.matching.domain.EducationLevel;
import com.gitory.backend.matching.domain.MatchEducation;

public record MatchEducationResponse(EducationLevel level, boolean exempt) {

    public static MatchEducationResponse from(MatchEducation education) {

        return education == null ? null : new MatchEducationResponse(education.level(), education.exempt());

    }
}
