package com.gitory.backend.card.domain.interview;

import com.gitory.backend.recommend.domain.CandidateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSourceTypeTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(value = {"null, DIRECT_CARD", "PR, PR", "COMMIT_CLUSTER, COMMIT_CLUSTER", "MANUAL, COMMIT_CLUSTER"},
            nullValues = "null")
    @DisplayName("후보 종류가 없으면 DIRECT_CARD, PR 이면 PR, 커밋 묶음과 직접 만든 후보는 COMMIT_CLUSTER 다")
    void decidesFromCandidateType(CandidateType candidateType, InterviewSourceType expected) {

        assertThat(InterviewSourceType.of(candidateType)).isEqualTo(expected);

    }
}
