package com.gitory.backend.matching.domain;

import com.gitory.backend.profile.domain.InterestCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class InterestCategoryTest {

    @Test
    @DisplayName("인적사항의 관심 분야 이름은 공고 직무 분류와 같다 — profile 이 matching 을 참조할 수 없어 따로 두므로 이 시험이 둘을 맞춘다")
    void interestNamesMatchJobCategories() {

        assertThat(Arrays.stream(InterestCategory.values()).map(Enum::name))
                .containsExactly(Arrays.stream(JobCategory.values()).map(Enum::name).toArray(String[]::new));

    }
}
