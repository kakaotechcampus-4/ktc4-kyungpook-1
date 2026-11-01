package com.gitory.backend.matching.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JobPostingTest {

    private static final Instant NOW = Instant.parse("2026-10-24T05:00:00Z");
    private static final Instant ALWAYS_OPEN = Instant.parse("2999-12-31T14:30:00Z");

    @Test
    @DisplayName("1년 넘게 뒤의 마감일은 상시 채용으로 보고 처음 저장할 때와 다시 볼 때 모두 마감일 없음으로 둔다")
    void farDeadlineIsStoredAsNoDeadline() {

        JobPosting posting = discover(ALWAYS_OPEN);
        assertThat(posting.getExpiresAt()).isNull();

        posting.seenAgain("라인플러스", "Backend Software Engineer", "https://careers.example.com/1", null,
                ALWAYS_OPEN, NOW.plus(Duration.ofDays(1)));
        assertThat(posting.getExpiresAt()).isNull();

    }

    @Test
    @DisplayName("1년 안의 마감일은 그대로 둔다")
    void nearDeadlineIsKept() {

        Instant deadline = NOW.plus(Duration.ofDays(30));

        assertThat(discover(deadline).getExpiresAt()).isEqualTo(deadline);

    }

    private static JobPosting discover(Instant expiresAt) {

        return JobPosting.discovered("line", "1", "라인플러스", "Backend Software Engineer",
                "https://careers.example.com/1", null, expiresAt, NOW);

    }
}
