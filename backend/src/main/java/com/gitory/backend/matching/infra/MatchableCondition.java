package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.EducationLevel;
import com.gitory.backend.matching.domain.GeneralConditionType;
import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingCriterion;

import java.util.List;

public record MatchableCondition(JobPosting posting, Long criterionId, CriterionType type, GeneralConditionType generalType,
                                 List<String> techs, EducationLevel educationLevel, boolean educationExempt) {

    public static MatchableCondition of(JobPosting posting, JobPostingCriterion criterion) {

        return new MatchableCondition(posting, criterion.getId(), criterion.getType(), criterion.getGeneralType(),
                criterion.getTechs(), criterion.getEducationLevel(), criterion.isEducationExempt());

    }
}
