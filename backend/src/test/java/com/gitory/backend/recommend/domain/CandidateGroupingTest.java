package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class CandidateGroupingTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);

    @Test
    @DisplayName("첫 경험 그룹화 기록은 받은 지문·경험 그룹화 버전과 지금 시각을 남긴다")
    void firstGroupingKeepsInputAndTime() {

        Instant before = Instant.now();

        CandidateGrouping grouping = CandidateGrouping.of(10L, FINGERPRINT, 1);

        assertThat(grouping.getUserRepositoryId()).isEqualTo(10L);
        assertThat(grouping.getInputFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(grouping.getGroupingVersion()).isEqualTo(1);
        assertThat(grouping.getGroupedAt()).isBetween(before, Instant.now());

    }

    @Test
    @DisplayName("지문과 경험 그룹화 버전이 모두 지난번과 같을 때만 같은 입력이다")
    void sameInputNeedsSameFingerprintAndVersion() {

        CandidateGrouping grouping = CandidateGrouping.of(10L, FINGERPRINT, 1);

        assertThat(grouping.hasSameInput(FINGERPRINT, 1)).isTrue();
        assertThat(grouping.hasSameInput(OTHER_FINGERPRINT, 1)).isFalse();
        assertThat(grouping.hasSameInput(FINGERPRINT, 2)).isFalse();

    }

    @Test
    @DisplayName("다시 기록하면 지문·경험 그룹화 버전을 이번 값으로 바꾸고 경험 그룹화 시각을 지금으로 바꾼다")
    void recordReplacesInputAndTime() {

        CandidateGrouping grouping = CandidateGrouping.of(10L, FINGERPRINT, 1);
        Instant before = Instant.now();

        grouping.record(OTHER_FINGERPRINT, 2);

        assertThat(grouping.getInputFingerprint()).isEqualTo(OTHER_FINGERPRINT);
        assertThat(grouping.getGroupingVersion()).isEqualTo(2);
        assertThat(grouping.getGroupedAt()).isBetween(before, Instant.now());
        assertThat(grouping.hasSameInput(OTHER_FINGERPRINT, 2)).isTrue();

    }

    @Test
    @DisplayName("지문은 경험 그룹화 버전·줄바꿈·요청을 이은 글의 SHA-256 을 16진수 64자로 쓴 값이다")
    void fingerprintIsSha256OfVersionAndRequest() {

        assertThat(CandidateGrouping.fingerprintOf("{\"commits\":[]}", 1))
                .isEqualTo("8f11b58ad3ed3eb1504ec3333a9b3c7c2a365253c0e7e9c7a802251964f76e34");

    }

    @Test
    @DisplayName("요청과 경험 그룹화 버전이 같으면 지문이 같고, 요청이 한 글자라도 다르거나 경험 그룹화 버전이 다르면 지문이 다르다")
    void fingerprintFollowsRequestAndVersion() {

        String request = "{\"commits\":[\"a\"]}";
        String fingerprint = CandidateGrouping.fingerprintOf(request, 1);

        assertThat(CandidateGrouping.fingerprintOf(new String(request), 1)).isEqualTo(fingerprint);
        assertThat(CandidateGrouping.fingerprintOf("{\"commits\":[\"b\"]}", 1)).isNotEqualTo(fingerprint);
        assertThat(CandidateGrouping.fingerprintOf(request, 2)).isNotEqualTo(fingerprint);

    }
}
