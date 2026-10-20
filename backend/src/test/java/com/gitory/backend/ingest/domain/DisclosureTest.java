package com.gitory.backend.ingest.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisclosureTest {

    private static final IngestLimits LIMITS = new IngestLimits(1000, 50, 50, 50);

    @Test
    @DisplayName("센 저장소는 읽는 것 4줄에 내 커밋 수를 넣고, 안 읽는 것 3줄을 돌려준다")
    void describesCountedRepository() {

        Disclosure disclosure = Disclosure.of(counted(188, 43, 58, 20), new IngestLimits(1000, 50, 40, 30), false);

        assertThat(disclosure.reads()).containsExactly(
                "기본 브랜치 커밋 기록 (다른 사람 커밋 포함, 최대 1000개)",
                "카드에는 내 커밋 43개만 써요",
                "최근 PR 50개·그보다 오래된 내 PR 최대 40개·최근 이슈 30개와 리뷰의 제목·본문 앞부분",
                "카드로 고른 커밋의 코드 변경 부분");
        assertThat(disclosure.skips()).containsExactly("비공개 저장소", "기본 브랜치가 아닌 브랜치", "저장소 파일 전체 내용");

    }

    @Test
    @DisplayName("경험 그룹화를 끈 서버의 예상 시간은 기본 5초에 GitHub 호출(커밋 수 + PR 수 × 3)마다 0.4초를 더해 반올림한다")
    void estimatesSecondsFromGithubCalls() {

        assertThat(Disclosure.of(counted(187, 43, 30, 10), LIMITS, false).estimatedSeconds()).isEqualTo(116);

    }

    @Test
    @DisplayName("경험 그룹화를 켰으면 기본 15초에 내 PR 수 × 2.7초를 더한다")
    void addsGroupingSecondsWhenGroupingIsEnabled() {

        assertThat(Disclosure.of(counted(188, 43, 30, 10), LIMITS, true).estimatedSeconds()).isEqualTo(158);

    }

    @Test
    @DisplayName("커밋 수는 수집 상한(1000개)까지만 계산에 넣는다")
    void capsCommitsAtCollectionLimit() {

        assertThat(Disclosure.of(counted(5000, 100, 30, 10), LIMITS, false).estimatedSeconds()).isEqualTo(441);

    }

    @Test
    @DisplayName("수집 PR 수는 최근 PR 수(상한까지)에, 내 PR 이 모두 그 밖에 있다고 보고 그 밖의 PR 수와 내 PR 상한을 넘지 않는 만큼 내 PR 수를 더해 센다")
    void countsOlderOwnPullRequestsBeyondRecentLimit() {

        assertThat(Disclosure.of(counted(0, 0, 200, 80), LIMITS, false).estimatedSeconds()).isEqualTo(125);
        assertThat(Disclosure.of(counted(0, 0, 30, 20), LIMITS, false).estimatedSeconds()).isEqualTo(41);
        assertThat(Disclosure.of(counted(0, 0, 200, 10), LIMITS, false).estimatedSeconds()).isEqualTo(77);

    }

    @Test
    @DisplayName("경험 그룹화의 내 PR 수는 최근 PR 상한과 내 PR 상한을 합친 수에서 자른다")
    void capsGroupingPullRequestsAtRecentPlusOwnLimit() {

        IngestLimits limits = new IngestLimits(1000, 50, 40, 30);

        int withGrouping = Disclosure.of(counted(0, 0, 300, 200), limits, true).estimatedSeconds();
        int withoutGrouping = Disclosure.of(counted(0, 0, 300, 200), limits, false).estimatedSeconds();

        assertThat(withGrouping - withoutGrouping).isEqualTo(258);

    }

    @Test
    @DisplayName("아직 못 센 저장소는 경험 그룹화를 켜도 내 커밋 수 없이 안내하고 예상 시간은 60초다")
    void describesUncountedRepositoryWithoutNumbers() {

        Disclosure disclosure = Disclosure.of(uncounted(), LIMITS, true);

        assertThat(disclosure.reads()).contains("카드에는 내 커밋만 써요");
        assertThat(disclosure.estimatedSeconds()).isEqualTo(60);

    }

    private static RepositorySummary counted(int commitCount, int ownCommitCount, int prCount, int ownPrCount) {

        return summary(Instant.parse("2026-10-05T01:00:00Z"), commitCount, ownCommitCount, prCount, ownPrCount);

    }

    private static RepositorySummary uncounted() {

        return summary(null, 0, 0, 0, 0);

    }

    private static RepositorySummary summary(Instant countedAt, int commitCount, int ownCommitCount, int prCount,
                                              int ownPrCount) {

        return new RepositorySummary(1L, UUID.randomUUID(), "grow22", "gitory", "Java", null, null, null, countedAt,
                commitCount, ownCommitCount, prCount, ownPrCount, 0);

    }
}
