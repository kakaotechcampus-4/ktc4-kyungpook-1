package com.gitory.backend.ingest.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ContributionTest {

    @Test
    @DisplayName("팀 커밋은 전체에서 내 것을 뺀 수이고 비율은 내 것 ÷ 전체다")
    void teamIsTotalMinusMine() {

        Contribution contribution = Contribution.of(52, 197);

        assertThat(contribution.mine()).isEqualTo(52);
        assertThat(contribution.team()).isEqualTo(145);
        assertThat(contribution.ratio()).isCloseTo(0.264, within(0.001));

    }

    @Test
    @DisplayName("내 커밋이 0개면 NONE 이고, 커밋이 하나도 없는 저장소도 비율 0 의 NONE 이다")
    void noOwnCommitIsNone() {

        assertThat(Contribution.of(0, 30).level()).isEqualTo(ContributionLevel.NONE);
        assertThat(Contribution.of(0, 0).level()).isEqualTo(ContributionLevel.NONE);
        assertThat(Contribution.of(0, 0).ratio()).isZero();

    }

    @Test
    @DisplayName("내 비율이 20% 미만이면 PARTIAL, 20% 이상 50% 미만이면 SHARED, 50% 이상이면 MAJOR 다")
    void levelFollowsRatio() {

        assertThat(Contribution.of(19, 100).level()).isEqualTo(ContributionLevel.PARTIAL);
        assertThat(Contribution.of(20, 100).level()).isEqualTo(ContributionLevel.SHARED);
        assertThat(Contribution.of(49, 100).level()).isEqualTo(ContributionLevel.SHARED);
        assertThat(Contribution.of(50, 100).level()).isEqualTo(ContributionLevel.MAJOR);
        assertThat(Contribution.of(41, 41).level()).isEqualTo(ContributionLevel.MAJOR);

    }

    @Test
    @DisplayName("경고가 없는 저장소(SHARED·MAJOR) 중 내 커밋이 10개 이상인 것만 추천한다")
    void recommendsOnlyMeaningfulRepositories() {

        assertThat(Contribution.of(10, 20).recommended()).isTrue();
        assertThat(Contribution.of(41, 41).recommended()).isTrue();
        assertThat(Contribution.of(9, 9).recommended()).isFalse();
        assertThat(Contribution.of(50, 300).recommended()).isFalse();

    }
}
