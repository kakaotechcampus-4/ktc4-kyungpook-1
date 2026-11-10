package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicantTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    @DisplayName("관심 직무를 골랐으면 공고 직무가 그중 하나일 때만 남긴다")
    void keepsOnlyInterestedCategories() {

        Applicant applicant = new Applicant(Set.of(JobCategory.BACKEND, JobCategory.DEVOPS));

        assertThat(applicant.allows(posting(JobCategory.BACKEND, ExperienceLevel.ENTRY, null))).isTrue();
        assertThat(applicant.allows(posting(JobCategory.FRONTEND, ExperienceLevel.ENTRY, null))).isFalse();

    }

    @Test
    @DisplayName("관심 직무를 고르지 않았거나 공고 직무가 비어 있으면 직무로 거르지 않는다")
    void doesNotFilterCategoryWhenEitherSideIsEmpty() {

        Applicant noInterest = new Applicant(Set.of());
        Applicant backend = new Applicant(Set.of(JobCategory.BACKEND));

        assertThat(noInterest.allows(posting(JobCategory.FRONTEND, ExperienceLevel.ENTRY, null))).isTrue();
        assertThat(backend.allows(posting(null, ExperienceLevel.ENTRY, null))).isTrue();

    }

    @Test
    @DisplayName("학력 조건은 수준을 읽었으면 늘 확인 필요(UNKNOWN)이고, 못 읽었으면 CHECK 다")
    void educationConditionIsAlwaysUnknownWhenLevelIsReadable() {

        Applicant applicant = new Applicant(Set.of());

        assertThat(applicant.educationOf(education(CriterionType.REQUIRED, EducationLevel.BACHELOR, false)))
                .isEqualTo(MatchConditionStatus.UNKNOWN);
        assertThat(applicant.educationOf(education(CriterionType.REQUIRED, EducationLevel.MASTER, true)))
                .isEqualTo(MatchConditionStatus.UNKNOWN);
        assertThat(applicant.educationOf(education(CriterionType.REQUIRED, null, false)))
                .isEqualTo(MatchConditionStatus.CHECK);

    }

    @Test
    @DisplayName("경력 조건은 연수 요구가 없으면 null, 있으면 늘 확인 필요(UNKNOWN)다")
    void careerConditionIsAlwaysUnknownWhenYearsAreRequired() {

        Applicant applicant = new Applicant(Set.of());

        assertThat(applicant.careerOf(posting(JobCategory.BACKEND, ExperienceLevel.ENTRY, null))).isNull();
        assertThat(applicant.careerOf(posting(JobCategory.BACKEND, ExperienceLevel.EXPERIENCED, 3)))
                .isEqualTo(MatchConditionStatus.UNKNOWN);

    }

    @Test
    @DisplayName("학력·경력 요구가 있는 공고도 직무만 맞으면 거르지 않는다")
    void doesNotFilterByEducationOrCareer() {

        Applicant applicant = new Applicant(Set.of(JobCategory.BACKEND));

        assertThat(applicant.allows(posting(JobCategory.BACKEND, ExperienceLevel.EXPERIENCED, 10))).isTrue();

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
