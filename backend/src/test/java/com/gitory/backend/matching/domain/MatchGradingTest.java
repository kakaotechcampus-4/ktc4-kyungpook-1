package com.gitory.backend.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchGradingTest {

    @ParameterizedTest
    @CsvSource({"3, 3, 0, 0, A", "4, 2, 0, 0, B", "3, 2, 0, 0, B", "3, 1, 0, 0, C", "2, 0, 0, 0, D"})
    @DisplayName("필수 조건이 전부면 A, 절반 이상이면 B, 하나 이상이면 C, 없으면 D 다")
    void gradesByRequiredConditions(int total, int met, int preferredTotal, int preferredMet, MatchGrade expected) {

        assertThat(MatchGrading.fit(new MatchCount(total, met), new MatchCount(preferredTotal, preferredMet)))
                .isEqualTo(expected);

    }

    @ParameterizedTest
    @CsvSource({"2, 2, A", "4, 2, B", "3, 1, C", "2, 0, D"})
    @DisplayName("필수 조건이 없는 공고는 우대 조건으로 같은 규칙을 쓴다")
    void gradesByPreferredWhenNoRequired(int total, int met, MatchGrade expected) {

        assertThat(MatchGrading.fit(new MatchCount(0, 0), new MatchCount(total, met))).isEqualTo(expected);

    }

    @ParameterizedTest
    @CsvSource({"3, 0, 5, 5, D", "3, 3, 2, 0, A"})
    @DisplayName("필수 조건이 있으면 우대 조건은 등급에 넣지 않는다")
    void ignoresPreferredWhenRequiredExists(int total, int met, int preferredTotal, int preferredMet,
                                            MatchGrade expected) {

        assertThat(MatchGrading.fit(new MatchCount(total, met), new MatchCount(preferredTotal, preferredMet)))
                .isEqualTo(expected);

    }

    @Test
    @DisplayName("필수·우대 조건이 모두 없으면 A 로 보내지 않고 등급 대상이 아니라고 막는다")
    void rejectsPostingWithoutGradedConditions() {

        assertThatThrownBy(() -> MatchGrading.fit(new MatchCount(0, 0), new MatchCount(0, 0)))
                .isInstanceOf(IllegalArgumentException.class);

    }
}
