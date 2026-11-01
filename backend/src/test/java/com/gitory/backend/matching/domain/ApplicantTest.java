package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.profile.domain.Career;
import com.gitory.backend.profile.domain.Education;
import com.gitory.backend.profile.domain.EducationStatus;
import com.gitory.backend.profile.domain.EmploymentType;
import com.gitory.backend.profile.domain.ProfileView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicantTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final YearMonth THIS_MONTH = YearMonth.of(2026, 10);

    @Test
    @DisplayName("관심 직무를 골랐으면 공고 직무가 그중 하나일 때만 남긴다")
    void keepsOnlyInterestedCategories() {

        Applicant applicant = new Applicant(Set.of(JobCategory.BACKEND, JobCategory.DEVOPS), null, null);

        assertThat(applicant.allows(posting(JobCategory.BACKEND, ExperienceLevel.ENTRY, null), List.of())).isTrue();
        assertThat(applicant.allows(posting(JobCategory.FRONTEND, ExperienceLevel.ENTRY, null), List.of())).isFalse();

    }

    @Test
    @DisplayName("관심 직무를 고르지 않았거나 공고 직무가 비어 있으면 직무로 거르지 않는다")
    void doesNotFilterCategoryWhenEitherSideIsEmpty() {

        Applicant noInterest = new Applicant(Set.of(), null, null);
        Applicant backend = new Applicant(Set.of(JobCategory.BACKEND), null, null);

        assertThat(noInterest.allows(posting(JobCategory.FRONTEND, ExperienceLevel.ENTRY, null), List.of())).isTrue();
        assertThat(backend.allows(posting(null, ExperienceLevel.ENTRY, null), List.of())).isTrue();

    }

    @Test
    @DisplayName("필수 학력 조건보다 내 가장 높은 학력이 낮으면 거른다")
    void filtersWhenHighestEducationIsBelowRequired() {

        List<JobPostingCriterion> bachelor = List.of(education(CriterionType.REQUIRED, EducationLevel.BACHELOR, false));

        assertThat(applicant(profile(List.of(school(EducationLevel.ASSOCIATE, EducationStatus.GRADUATED)), List.of()))
                .allows(entry(), bachelor)).isFalse();
        assertThat(applicant(profile(List.of(school(EducationLevel.ASSOCIATE, EducationStatus.GRADUATED),
                school(EducationLevel.MASTER, EducationStatus.GRADUATED)), List.of()))
                .allows(entry(), bachelor)).isTrue();

    }

    @Test
    @DisplayName("재학·휴학·졸업 예정은 그 학력으로 치고 중퇴는 치지 않는다")
    void countsEnrolledButNotDroppedOut() {

        Applicant enrolled = applicant(profile(List.of(school(EducationLevel.BACHELOR, EducationStatus.ENROLLED)), List.of()));
        Applicant onLeave = applicant(profile(List.of(school(EducationLevel.BACHELOR, EducationStatus.ON_LEAVE)), List.of()));
        Applicant dropped = applicant(profile(List.of(school(EducationLevel.HIGH_SCHOOL, EducationStatus.GRADUATED),
                school(EducationLevel.BACHELOR, EducationStatus.DROPPED_OUT)), List.of()));

        assertThat(enrolled.education()).isEqualTo(EducationLevel.BACHELOR);
        assertThat(onLeave.education()).isEqualTo(EducationLevel.BACHELOR);
        assertThat(dropped.education()).isEqualTo(EducationLevel.HIGH_SCHOOL);

    }

    @Test
    @DisplayName("우대 조건의 학력과 예외가 적힌 필수 학력으로는 거르지 않는다")
    void ignoresPreferredOrExemptEducation() {

        Applicant highSchool = applicant(profile(List.of(school(EducationLevel.HIGH_SCHOOL, EducationStatus.GRADUATED)),
                List.of()));

        assertThat(highSchool.allows(entry(), List.of(education(CriterionType.PREFERRED, EducationLevel.MASTER, false))))
                .isTrue();
        assertThat(highSchool.allows(entry(), List.of(education(CriterionType.REQUIRED, EducationLevel.BACHELOR, true))))
                .isTrue();

    }

    @Test
    @DisplayName("학력을 적지 않았으면 학력으로 거르지 않는다")
    void doesNotFilterEducationWhenEmpty() {

        Applicant noEducation = applicant(profile(List.of(), List.of()));

        assertThat(noEducation.allows(entry(), List.of(education(CriterionType.REQUIRED, EducationLevel.DOCTOR, false))))
                .isTrue();

    }

    @Test
    @DisplayName("공고의 최소 경력 연수보다 내 경력이 모자라면 거른다")
    void filtersWhenCareerIsShorterThanRequired() {

        Applicant twoYears = applicant(profile(List.of(),
                List.of(job(EmploymentType.FULL_TIME, "2024-01", "2025-12"))));

        assertThat(twoYears.allows(posting(JobCategory.BACKEND, ExperienceLevel.EXPERIENCED, 2), List.of())).isTrue();
        assertThat(twoYears.allows(posting(JobCategory.BACKEND, ExperienceLevel.EXPERIENCED, 3), List.of())).isFalse();

    }

    @Test
    @DisplayName("경력은 정규직·계약직 달만 겹치지 않게 세고 재직 중이면 이번 달까지 센다")
    void countsFullTimeAndContractMonthsOnce() {

        Applicant applicant = applicant(profile(List.of(), List.of(
                job(EmploymentType.FULL_TIME, "2025-01", "2025-06"),
                job(EmploymentType.CONTRACT, "2025-04", "2025-09"),
                job(EmploymentType.INTERN, "2024-01", "2024-12"),
                job(EmploymentType.PART_TIME, "2023-01", "2023-12"),
                job(EmploymentType.FREELANCE, "2022-01", "2022-12"),
                job(EmploymentType.FULL_TIME, "2026-09", null))));

        assertThat(applicant.careerMonths()).isEqualTo(9 + 2);

    }

    @Test
    @DisplayName("인적사항을 저장했는데 경력이 비어 있으면 0년으로 보고, 저장한 적이 없으면 경력으로 거르지 않는다")
    void treatsSavedEmptyCareerAsZeroButUnsavedAsUnknown() {

        JobPosting oneYear = posting(JobCategory.BACKEND, ExperienceLevel.EXPERIENCED, 1);
        Applicant saved = applicant(profile(List.of(), List.of()));
        Applicant neverSaved = Applicant.of(new ProfileView(List.of(), List.of(), List.of(), List.of(), null),
                Set.of(), THIS_MONTH);

        assertThat(saved.allows(oneYear, List.of())).isFalse();
        assertThat(neverSaved.allows(oneYear, List.of())).isTrue();

    }

    private static Applicant applicant(ProfileView profile) {

        return Applicant.of(profile, Set.of(), THIS_MONTH);

    }

    private static ProfileView profile(List<Education> educations, List<Career> careers) {

        return new ProfileView(educations, careers, List.of(), List.of(), NOW);

    }

    private static Education school(EducationLevel level, EducationStatus status) {

        return new Education(com.gitory.backend.profile.domain.EducationLevel.valueOf(level.name()), "학교", null,
                status, null, null, null, null);

    }

    private static Career job(EmploymentType type, String startMonth, String endMonth) {

        return new Career("회사", null, type, startMonth, endMonth);

    }

    private static JobPosting entry() {

        return posting(JobCategory.BACKEND, ExperienceLevel.ENTRY, null);

    }

    private static JobPosting posting(JobCategory category, ExperienceLevel level, Integer minYears) {

        JobPosting posting = JobPosting.discovered("acme", "1", "에이크미", "백엔드 개발자",
                "https://jobs.example.com/1", null, null, NOW);
        posting.extracted(category, level, minYears, null, List.<CardTech>of(), 2, JobPostingContentStatus.READY,
                "a".repeat(64), NOW);
        return posting;

    }

    private static JobPostingCriterion education(CriterionType type, EducationLevel level, boolean exempt) {

        return JobPostingCriterion.extracted(1L, type, 1, "학력", "학력", GeneralConditionType.EDUCATION, List.of(),
                level, exempt);

    }
}
