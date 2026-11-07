package com.gitory.backend.matching.domain;

import com.gitory.backend.profile.domain.Career;
import com.gitory.backend.profile.domain.EducationStatus;
import com.gitory.backend.profile.domain.EmploymentType;
import com.gitory.backend.profile.domain.ProfileView;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 인적사항에서 1단계 거르기에 쓰는 값만 뽑은 것이며, 비어 있는 값으로는 거르지 않는다
 * 잘못 거르면 맞는 공고가 추천에서 아예 사라지므로, 애매한 값은 거르지 않는 쪽으로 읽는다
 */
public record Applicant(Set<JobCategory> interests, EducationLevel education, Integer careerMonths) {

    // 공고의 '경력 N년' 에는 보통 인턴·아르바이트·프리랜서 기간이 들어가지 않는다
    private static final Set<EmploymentType> CAREER_TYPES = EnumSet.of(EmploymentType.FULL_TIME,
            EmploymentType.CONTRACT);
    private static final int MONTHS_PER_YEAR = 12;

    /** 인적사항을 한 번도 저장하지 않았으면 경력을 모르는 것으로 두고, 저장했는데 경력이 비어 있으면 0개월로 본다 */
    public static Applicant of(ProfileView profile, Set<JobCategory> interests, YearMonth now) {

        // 신입 공고는 졸업 예정자를 받는 경우가 많아 재학·휴학도 그 학력으로 친다
        EducationLevel education = profile.educations().stream()
                .filter(entry -> entry.status() != EducationStatus.DROPPED_OUT)
                .map(entry -> EducationLevel.valueOf(entry.level().name()))
                .max(Comparator.naturalOrder())
                .orElse(null);
        Integer careerMonths = profile.savedAt() == null ? null : careerMonths(profile.careers(), now);
        return new Applicant(Set.copyOf(interests), education, careerMonths);

    }

    private static int careerMonths(List<Career> careers, YearMonth now) {

        // 겹쳐 다닌 달을 두 번 세지 않도록 달 단위로 모은다
        Set<YearMonth> months = new HashSet<>();
        for (Career career : careers) {
            if (!CAREER_TYPES.contains(career.type())) {
                continue;
            }
            YearMonth end = career.endMonth() == null ? now : YearMonth.parse(career.endMonth());
            for (YearMonth month = YearMonth.parse(career.startMonth()); !month.isAfter(end); month = month.plusMonths(1)) {
                months.add(month);
            }
        }
        return months.size();

    }

    /**
     * 공고 상세에서 학력 조건 하나를 인적사항과 견주며, 수준을 못 읽은 조건은 견줄 것이 없어 CHECK, 학력을 모르면 UNKNOWN 이다
     * '또는 그에 준하는 경력' 같은 예외가 적힌 조건은 못 미쳐도 지원할 수 있을 수 있어 NOT_MET 대신 CHECK 다
     */
    public MatchConditionStatus educationOf(JobPostingCriterion criterion) {

        if (criterion.getEducationLevel() == null) {
            return MatchConditionStatus.CHECK;
        }
        if (education == null) {
            return MatchConditionStatus.UNKNOWN;
        }
        if (education.compareTo(criterion.getEducationLevel()) >= 0) {
            return MatchConditionStatus.MET;
        }
        return criterion.isEducationExempt() ? MatchConditionStatus.CHECK : MatchConditionStatus.NOT_MET;

    }

    /** 공고가 요구한 경력 연수를 인적사항과 견주며, 연수 요구가 없는 공고는 null 이다 */
    public MatchConditionStatus careerOf(JobPosting posting) {

        Integer requiredYears = posting.getExperienceMinYears();
        if (requiredYears == null) {
            return null;
        }
        if (careerMonths == null) {
            return MatchConditionStatus.UNKNOWN;
        }
        return careerMonths >= requiredYears * MONTHS_PER_YEAR ? MatchConditionStatus.MET
                : MatchConditionStatus.NOT_MET;

    }

    public boolean allows(JobPosting posting, List<JobPostingCriterion> criteria) {

        return allowsCategory(posting) && allowsEducation(criteria) && allowsCareer(posting);

    }

    private boolean allowsCategory(JobPosting posting) {

        return interests.isEmpty() || posting.getCategory() == null || interests.contains(posting.getCategory());

    }

    // 우대 조건의 학력과 '또는 그에 준하는 경력' 같은 예외가 적힌 학력은 지원 자격을 막지 않는다
    private boolean allowsEducation(List<JobPostingCriterion> criteria) {

        return education == null || criteria.stream()
                .filter(criterion -> criterion.getType() == CriterionType.REQUIRED)
                .filter(criterion -> criterion.getEducationLevel() != null && !criterion.isEducationExempt())
                .allMatch(criterion -> education.compareTo(criterion.getEducationLevel()) >= 0);

    }

    private boolean allowsCareer(JobPosting posting) {

        Integer requiredYears = posting.getExperienceMinYears();
        return careerMonths == null || requiredYears == null || careerMonths >= requiredYears * MONTHS_PER_YEAR;

    }
}
