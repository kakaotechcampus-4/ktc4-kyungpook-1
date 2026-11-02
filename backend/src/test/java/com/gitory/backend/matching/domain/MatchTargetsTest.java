package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.MatchableCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchTargetsTest {

    @Test
    @DisplayName("기술은 필수·우대 조건을 조건 순서대로 훑어 처음 나온 순서로 한 번씩 둔다")
    void techsInConditionOrderWithoutDuplicates() {

        List<MatchableCondition> conditions = List.of(
                tech(CriterionType.REQUIRED, "JAVA", "SPRING"),
                tech(CriterionType.PREFERRED, "KAFKA", "JAVA"),
                tech(CriterionType.REQUIRED, "SPRING", "POSTGRESQL"));

        assertThat(MatchTargets.techsOf(conditions)).containsExactly("JAVA", "SPRING", "KAFKA", "POSTGRESQL");

    }

    @Test
    @DisplayName("상세 행에 섞여 오는 담당 업무 조건의 기술은 넣지 않는다")
    void techsSkipResponsibilities() {

        List<MatchableCondition> conditions = List.of(
                tech(CriterionType.RESPONSIBILITY, "GO"),
                tech(CriterionType.REQUIRED, "JAVA"));

        assertThat(MatchTargets.techsOf(conditions)).containsExactly("JAVA");

    }

    @Test
    @DisplayName("학력은 상세처럼 수준을 읽은 EDUCATION 조건만 보고, 우대 학력은 빼고 필수 학력 중 가장 낮은 수준을 둔다")
    void educationIsLowestReadRequiredLevel() {

        List<MatchableCondition> conditions = List.of(
                education(CriterionType.PREFERRED, EducationLevel.HIGH_SCHOOL, false),
                education(CriterionType.REQUIRED, null, false),
                education(CriterionType.REQUIRED, EducationLevel.MASTER, false),
                education(CriterionType.REQUIRED, EducationLevel.BACHELOR, false));

        assertThat(MatchTargets.educationOf(conditions)).isEqualTo(new MatchEducation(EducationLevel.BACHELOR, false));

    }

    @Test
    @DisplayName("같은 수준에 예외 없는 학력이 하나라도 있으면 예외가 아니고, 예외 학력만 있으면 예외다")
    void educationExemptOnlyWhenEveryLowestIsExempt() {

        assertThat(MatchTargets.educationOf(List.of(
                education(CriterionType.REQUIRED, EducationLevel.BACHELOR, true),
                education(CriterionType.REQUIRED, EducationLevel.BACHELOR, false))))
                .isEqualTo(new MatchEducation(EducationLevel.BACHELOR, false));
        assertThat(MatchTargets.educationOf(List.of(education(CriterionType.REQUIRED, EducationLevel.MASTER, true))))
                .isEqualTo(new MatchEducation(EducationLevel.MASTER, true));

    }

    @Test
    @DisplayName("기술도 수준을 읽은 필수 학력도 없으면 기술은 [] 이고 학력은 null 이다")
    void emptyWhenNothingToShow() {

        List<MatchableCondition> conditions = List.of(
                tech(CriterionType.REQUIRED),
                new MatchableCondition(null, 9L, CriterionType.REQUIRED, GeneralConditionType.LOCATION, List.of(), null, false));

        assertThat(MatchTargets.techsOf(conditions)).isEmpty();
        assertThat(MatchTargets.educationOf(conditions)).isNull();

    }

    private static MatchableCondition tech(CriterionType type, String... techs) {

        return new MatchableCondition(null, 1L, type, null, List.of(techs), null, false);

    }

    private static MatchableCondition education(CriterionType type, EducationLevel level, boolean exempt) {

        return new MatchableCondition(null, 2L, type, GeneralConditionType.EDUCATION, List.of(), level, exempt);

    }
}
