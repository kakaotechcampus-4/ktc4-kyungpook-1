package com.gitory.backend.ingest.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisclosureTest {

    private static final IngestLimits LIMITS = new IngestLimits(1000, 50);

    @Test
    @DisplayName("센 저장소는 읽는 것 4줄에 내 커밋 수를 넣고, 안 읽는 것 3줄을 돌려준다")
    void describesCountedRepository() {

        Disclosure disclosure = Disclosure.of(counted(188, 43, 58), LIMITS);

        assertThat(disclosure.reads()).containsExactly(
                "기본 브랜치 커밋 기록 (다른 사람 커밋 포함, 최대 1000개)",
                "카드에는 내 커밋 43개만 써요",
                "최근 PR·이슈 50개씩과 리뷰의 제목·본문 앞부분",
                "카드로 고른 커밋의 코드 변경 부분");
        assertThat(disclosure.skips()).containsExactly("비공개 저장소", "기본 브랜치가 아닌 브랜치", "저장소 파일 전체 내용");

    }

    @Test
    @DisplayName("예상 시간은 기본 5초에 GitHub 호출(커밋 수 + PR 수 × 3)마다 0.2초를 더해 반올림한다")
    void estimatesSecondsFromGithubCalls() {

        assertThat(Disclosure.of(counted(188, 43, 30), LIMITS).estimatedSeconds()).isEqualTo(61);

    }

    @Test
    @DisplayName("커밋·PR 수는 수집 상한(1000개·50개)까지만 계산에 넣는다")
    void capsEstimateAtCollectionLimits() {

        assertThat(Disclosure.of(counted(5000, 100, 300), LIMITS).estimatedSeconds()).isEqualTo(235);

    }

    @Test
    @DisplayName("아직 못 센 저장소는 내 커밋 수 없이 안내하고 예상 시간은 60초다")
    void describesUncountedRepositoryWithoutNumbers() {

        Disclosure disclosure = Disclosure.of(uncounted(), LIMITS);

        assertThat(disclosure.reads()).contains("카드에는 내 커밋만 써요");
        assertThat(disclosure.estimatedSeconds()).isEqualTo(60);

    }

    private static RepositorySummary counted(int commitCount, int ownCommitCount, int prCount) {

        return summary(Instant.parse("2026-10-05T01:00:00Z"), commitCount, ownCommitCount, prCount);

    }

    private static RepositorySummary uncounted() {

        return summary(null, 0, 0, 0);

    }

    private static RepositorySummary summary(Instant countedAt, int commitCount, int ownCommitCount, int prCount) {

        return new RepositorySummary(UUID.randomUUID(), "grow22", "gitory", "Java", null, null, null, countedAt,
                commitCount, ownCommitCount, prCount, 0, 0);

    }
}
