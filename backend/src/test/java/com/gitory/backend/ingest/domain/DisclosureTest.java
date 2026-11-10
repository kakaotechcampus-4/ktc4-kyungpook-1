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
                "기본 브랜치 커밋 기록 (최대 1000개, 내용은 내 커밋만)",
                "카드에는 내 커밋 43개만 써요",
                "최근 PR 50개·그보다 오래된 내 PR 최대 40개·최근 이슈 30개와 리뷰의 제목·본문 앞부분",
                "카드로 고른 커밋의 코드 변경 부분");
        assertThat(disclosure.skips()).containsExactly("비공개 저장소", "기본 브랜치가 아닌 브랜치", "저장소 파일 전체 내용");

    }

    @Test
    @DisplayName("경험 그룹화를 끈 서버의 예상 시간은 기본 5초에 GitHub 호출(커밋 목록 100개당 1번 + 내 커밋 수 + PR 수 × 3)마다 0.4초를 더해 반올림한다")
    void estimatesSecondsFromGithubCalls() {

        assertThat(Disclosure.of(counted(187, 43, 30, 10), LIMITS, false).estimatedSeconds()).isEqualTo(59);

    }

    @Test
    @DisplayName("경험 그룹화를 켰으면 기본 15초에 내 PR 수 × 2.7초를 더한다")
    void addsGroupingSecondsWhenGroupingIsEnabled() {

        assertThat(Disclosure.of(counted(188, 43, 30, 10), LIMITS, true).estimatedSeconds()).isEqualTo(101);

    }

    @Test
    @DisplayName("커밋 목록과 내 커밋 수는 수집 상한(1000개)까지만 계산에 넣는다")
    void capsCommitsAtCollectionLimit() {

        assertThat(Disclosure.of(counted(5000, 3000, 30, 10), LIMITS, false).estimatedSeconds()).isEqualTo(445);

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

    @Test
    @DisplayName("분석한 적 없는 저장소는 새 커밋·새 PR·다시 분석 예상 시간이 모두 null 이다")
    void hasNoReanalyzeFieldsBeforeFirstAnalysis() {

        Disclosure disclosure = Disclosure.of(counted(188, 43, 30, 10), LIMITS, true);

        assertThat(disclosure.newCommits()).isNull();
        assertThat(disclosure.newPullRequests()).isNull();
        assertThat(disclosure.reanalyzeSeconds()).isNull();

    }

    @Test
    @DisplayName("분석했고 새 기록이 없으면 새 커밋·PR 은 0 이고 예상 시간은 기본 5초에 커밋 목록 쪽수만 더하며 그룹화 시간은 넣지 않는다")
    void reanalyzeWithNothingNewSkipsGrouping() {

        Disclosure disclosure = Disclosure.of(analyzed(188, 43, 30, 10, 188, 30), LIMITS, true);

        assertThat(disclosure.newCommits()).isZero();
        assertThat(disclosure.newPullRequests()).isZero();
        assertThat(disclosure.reanalyzeSeconds()).isEqualTo(6);

    }

    @Test
    @DisplayName("새 커밋 5개와 새 PR 2개면 호출 수에 더하고 새 PR 수 × 2.7초와 기본 15초 그룹화 시간도 더한다")
    void reanalyzeWithNewRecordsAddsCallsAndGrouping() {

        Disclosure disclosure = Disclosure.of(analyzed(188, 43, 30, 10, 183, 28), LIMITS, true);

        assertThat(disclosure.newCommits()).isEqualTo(5);
        assertThat(disclosure.newPullRequests()).isEqualTo(2);
        assertThat(disclosure.reanalyzeSeconds()).isEqualTo(31);
        assertThat(Disclosure.of(analyzed(188, 43, 30, 10, 183, 28), LIMITS, false).reanalyzeSeconds()).isEqualTo(10);

    }

    @Test
    @DisplayName("지난 정리 때 수가 지금 GitHub 수보다 많아도 새 커밋·PR 은 음수가 아니라 0 이다")
    void newRecordsAreNeverNegative() {

        Disclosure disclosure = Disclosure.of(analyzed(188, 43, 30, 10, 500, 90), LIMITS, true);

        assertThat(disclosure.newCommits()).isZero();
        assertThat(disclosure.newPullRequests()).isZero();

    }

    @Test
    @DisplayName("지난 정리 때 남긴 GitHub 수와 지금 수의 차이로 새 커밋·PR 을 센다")
    void countsNewRecordsFromGithubTotalsOfBothTimes() {

        Disclosure disclosure = Disclosure.of(analyzed(1000, 120, 70, 15, 900, 62), LIMITS, false);

        assertThat(disclosure.newCommits()).isEqualTo(100);
        assertThat(disclosure.newPullRequests()).isEqualTo(8);

    }

    @Test
    @DisplayName("커밋이 상한(1000)을 넘는 레포도 지금 수 - 그때 수로 센다")
    void countsNewCommitsOfRepositoryOverCap() {

        Disclosure disclosure = Disclosure.of(analyzed(1220, 120, 30, 10, 1200, 30), LIMITS, false);

        assertThat(disclosure.newCommits()).isEqualTo(20);
        assertThat(disclosure.newPullRequests()).isZero();

    }

    @Test
    @DisplayName("지난 정리 때 수를 남기지 않았으면 새 기록 수와 다시 정리 시간은 null 이다")
    void hasNoReanalyzeFieldsWithoutAnalyzedCounts() {

        Disclosure disclosure = Disclosure.of(analyzed(188, 43, 30, 10, null, null), LIMITS, true);

        assertThat(disclosure.newCommits()).isNull();
        assertThat(disclosure.newPullRequests()).isNull();
        assertThat(disclosure.reanalyzeSeconds()).isNull();

    }

    private static RepositorySummary analyzed(int commitCount, int ownCommitCount, int prCount, int ownPrCount,
                                             Integer analyzedCommits, Integer analyzedPrs) {

        return new RepositorySummary(1L, UUID.randomUUID(), "grow22", "gitory", "Java", null, null,
                Instant.parse("2026-10-06T01:00:00Z"), Instant.parse("2026-10-07T01:00:00Z"), commitCount,
                ownCommitCount, prCount, ownPrCount, 0, null, null, null, null, analyzedCommits, analyzedPrs);

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
                commitCount, ownCommitCount, prCount, ownPrCount, 0, null, null, null, null);

    }
}
