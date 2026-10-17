package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CandidateVerdictTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("후보가 0개면 부분 수집이어도 EMPTY 다")
    void noCandidateIsEmpty(boolean partial) {

        assertThat(CandidateVerdict.of(0, partial)).isEqualTo(CandidateVerdict.EMPTY);

    }

    @Test
    @DisplayName("후보가 하나라도 있으면 부분 수집일 때 PARTIAL, 아니면 OK 다")
    void candidatesArePartialOrOk() {

        assertThat(CandidateVerdict.of(1, true)).isEqualTo(CandidateVerdict.PARTIAL);
        assertThat(CandidateVerdict.of(1, false)).isEqualTo(CandidateVerdict.OK);
        assertThat(CandidateVerdict.of(12, false)).isEqualTo(CandidateVerdict.OK);

    }
}
