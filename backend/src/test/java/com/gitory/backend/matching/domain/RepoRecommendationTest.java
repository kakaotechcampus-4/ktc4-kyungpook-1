package com.gitory.backend.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoRecommendationTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    @DisplayName("계산하는 사이에 확정 카드가 또 바뀌었으면 계산을 마쳐도 COMPUTING 으로 남아 다음 계산을 기다린다")
    void staysComputingWhenChangedDuringCompute() {

        RepoRecommendation changedMeanwhile = RepoRecommendation.ready(1L, 7L, List.of(), NOW);
        ReflectionTestUtils.setField(changedMeanwhile, "staleSince", NOW);
        RepoRecommendation failedMeanwhile = RepoRecommendation.ready(1L, 8L, List.of(), NOW);
        ReflectionTestUtils.setField(failedMeanwhile, "staleSince", NOW);

        changedMeanwhile.refresh(List.of(), NOW.plusSeconds(30));
        failedMeanwhile.fail(NOW.plusSeconds(30));

        assertThat(changedMeanwhile.getStatus()).isEqualTo(RepoRecommendationStatus.COMPUTING);
        assertThat(failedMeanwhile.getStatus()).isEqualTo(RepoRecommendationStatus.COMPUTING);
        assertThat(RepoRecommendation.ready(1L, 9L, List.of(), NOW).getStatus())
                .isEqualTo(RepoRecommendationStatus.READY);
        assertThat(RepoRecommendation.failed(1L, 9L, NOW).getStatus()).isEqualTo(RepoRecommendationStatus.FAILED);

    }
}
