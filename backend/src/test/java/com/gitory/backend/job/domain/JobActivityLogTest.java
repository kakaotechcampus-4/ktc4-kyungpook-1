package com.gitory.backend.job.domain;

import com.gitory.backend.common.infra.ai.AiProgressItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class JobActivityLogTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T00:00:00Z"));

    private JobActivityLog log;

    @BeforeEach
    void setUp() {

        Clock clock = mock(Clock.class);
        given(clock.instant()).willAnswer(invocation -> now.get());
        log = new JobActivityLog(clock);

    }

    @Test
    @DisplayName("읽기 줄은 단계마다 최근 20줄만 남기고 판단 줄은 모두 남기며, Job 마다 받은 순서대로 1부터 번호를 붙인다")
    void keepsTwentyReadLinesPerStepAndEveryJudgmentLine() {

        for (int n = 1; n <= 3; n++) {
            log.append(1L, JobStepKey.PR_REVIEW, pullRequest(n));
        }
        for (int n = 1; n <= 25; n++) {
            log.append(1L, JobStepKey.COMMITS, commit("c" + n, null));
        }
        for (int n = 1; n <= 30; n++) {
            log.append(1L, JobStepKey.REASON, described("경험 " + n));
        }
        log.append(2L, JobStepKey.COMMITS, commit("other", null));

        List<JobActivity> lines = log.recent(1L);

        assertThat(lines).filteredOn(line -> line.kind() == JobActivityKind.PULL_REQUEST)
                .extracting(JobActivity::number).containsExactly(1, 2, 3);
        assertThat(lines).filteredOn(line -> line.kind() == JobActivityKind.COMMIT)
                .extracting(JobActivity::sha).containsExactlyElementsOf(range("c", 6, 25));
        assertThat(lines).filteredOn(line -> line.kind() == JobActivityKind.DESCRIBED).hasSize(30);
        assertThat(lines).extracting(JobActivity::seq).isSorted().endsWith(58L);
        assertThat(log.recent(2L)).extracting(JobActivity::seq, JobActivity::sha).containsExactly(
                tuple(1L, "other"));

    }

    @Test
    @DisplayName("종류나 뺀 이유를 모르거나 그 종류에 있어야 할 값이 빠진 줄은 남기지 않는다")
    void dropsUnknownOrIncompleteLines() {

        log.append(1L, JobStepKey.COMMITS, new AiProgressItem("SOMETHING_NEW", null, "새 종류", null, null, null, null,
                null, null, null));
        log.append(1L, JobStepKey.COMMITS, commit("squashed", "SQUASHED"));
        log.append(1L, JobStepKey.PR_REVIEW, new AiProgressItem("PULL_REQUEST", 31, "리뷰 수 없음", null, null, null,
                null, null, null, null));
        log.append(1L, JobStepKey.COMMITS, new AiProgressItem("COMMIT", null, null, null, "abc1234", 3, null, null, null,
                null));
        log.append(1L, JobStepKey.COMPRESS, new AiProgressItem("GROUPED", null, null, null, null, null, null, 12, null,
                null));
        log.append(1L, JobStepKey.REASON, new AiProgressItem("DESCRIBED", null, "이유 없음", null, null, null, null, null,
                null, null));
        log.append(1L, JobStepKey.COMMITS, commit("kept", "MERGE_COMMIT"));

        assertThat(log.recent(1L)).singleElement().satisfies(line -> {
            assertThat(line.seq()).isEqualTo(1);
            assertThat(line.sha()).isEqualTo("kept");
            assertThat(line.excluded()).isEqualTo(JobActivityExclusion.MERGE_COMMIT);
        });

    }

    @Test
    @DisplayName("Job 이 끝나고 10분이 지나면 줄을 지우고, 10분이 안 됐거나 끝나지 않은 Job 의 줄은 남긴다")
    void clearsLinesTenMinutesAfterTheJobFinishes() {

        log.append(1L, JobStepKey.COMMITS, commit("finished", null));
        log.append(2L, JobStepKey.COMMITS, commit("running", null));
        log.finish(1L);

        now.set(now.get().plus(Duration.ofMinutes(10)));
        assertThat(log.recent(1L)).hasSize(1);

        now.set(now.get().plusSeconds(1));
        assertThat(log.recent(1L)).isEmpty();
        assertThat(log.recent(2L)).hasSize(1);

    }

    private static AiProgressItem pullRequest(int number) {
        return new AiProgressItem("PULL_REQUEST", number, "PR " + number, 0, null, null, null, null, null, null);
    }

    private static AiProgressItem commit(String sha, String excluded) {
        return new AiProgressItem("COMMIT", null, "커밋 " + sha, null, sha, 1, excluded, null, null, null);
    }

    private static AiProgressItem described(String title) {
        return new AiProgressItem("DESCRIBED", null, title, null, null, null, null, null, null, "이유");
    }

    private static List<String> range(String prefix, int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(n -> prefix + n).toList();
    }
}
