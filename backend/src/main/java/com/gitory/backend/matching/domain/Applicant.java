package com.gitory.backend.matching.domain;

import java.util.Set;

/**
 * 프로필에서 1단계 거르기에 쓰는 값은 관심 분야뿐이며, 비어 있으면 직무로 거르지 않는다
 * 학력·경력은 받지 않아 공고 상세에서 늘 확인 필요로 보여 준다
 */
public record Applicant(Set<JobCategory> interests) {

    public static Applicant of(Set<JobCategory> interests) {

        return new Applicant(Set.copyOf(interests));

    }

    /** 학력을 받지 않아 수준을 읽은 조건은 UNKNOWN, 못 읽은 조건은 견줄 것이 없어 CHECK 다 */
    public MatchConditionStatus educationOf(JobPostingCriterion criterion) {

        return criterion.getEducationLevel() == null ? MatchConditionStatus.CHECK : MatchConditionStatus.UNKNOWN;

    }

    /** 경력을 받지 않아 연수 요구가 없는 공고는 null, 있는 공고는 UNKNOWN 이다 */
    public MatchConditionStatus careerOf(JobPosting posting) {

        return posting.getExperienceMinYears() == null ? null : MatchConditionStatus.UNKNOWN;

    }

    public boolean allows(JobPosting posting) {

        return interests.isEmpty() || posting.getCategory() == null || interests.contains(posting.getCategory());

    }
}
