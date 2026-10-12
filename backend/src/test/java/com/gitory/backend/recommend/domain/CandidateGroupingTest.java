package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class CandidateGroupingTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);

    @Test
    @DisplayName("처음 묶은 기록은 받은 지문·묶기 버전과 지금 시각을 남긴다")
    void firstGroupingKeepsInputAndTime() {

        Instant before = Instant.now();

        CandidateGrouping grouping = CandidateGrouping.of(10L, FINGERPRINT, 1);

        assertThat(grouping.getUserRepositoryId()).isEqualTo(10L);
        assertThat(grouping.getInputFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(grouping.getGroupingVersion()).isEqualTo(1);
        assertThat(grouping.getGroupedAt()).isBetween(before, Instant.now());

    }

    @Test
    @DisplayName("지문과 묶기 버전이 모두 지난번과 같을 때만 같은 입력이다")
    void sameInputNeedsSameFingerprintAndVersion() {

        CandidateGrouping grouping = CandidateGrouping.of(10L, FINGERPRINT, 1);

        assertThat(grouping.hasSameInput(FINGERPRINT, 1)).isTrue();
        assertThat(grouping.hasSameInput(OTHER_FINGERPRINT, 1)).isFalse();
        assertThat(grouping.hasSameInput(FINGERPRINT, 2)).isFalse();

    }

    @Test
    @DisplayName("다시 기록하면 지문·묶기 버전을 이번 값으로 바꾸고 묶은 시각을 지금으로 바꾼다")
    void recordReplacesInputAndTime() {

        CandidateGrouping grouping = CandidateGrouping.of(10L, FINGERPRINT, 1);
        Instant before = Instant.now();

        grouping.record(OTHER_FINGERPRINT, 2);

        assertThat(grouping.getInputFingerprint()).isEqualTo(OTHER_FINGERPRINT);
        assertThat(grouping.getGroupingVersion()).isEqualTo(2);
        assertThat(grouping.getGroupedAt()).isBetween(before, Instant.now());
        assertThat(grouping.hasSameInput(OTHER_FINGERPRINT, 2)).isTrue();

    }
}
