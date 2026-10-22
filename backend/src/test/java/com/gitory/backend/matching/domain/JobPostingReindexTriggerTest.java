package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.SyncLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class JobPostingReindexTriggerTest {

    private final JobPostingIndexService indexing = mock(JobPostingIndexService.class);
    private final SyncLock lock = mock(SyncLock.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T12:00:00Z"));
    private final Clock clock = mock(Clock.class);

    @BeforeEach
    void setUp() {

        when(clock.instant()).thenAnswer(call -> now.get());

    }

    @Test
    @DisplayName("잠금을 잡으면 활성 공고를 색인에 다시 보내고, 대기 중에는 새로 넘기지 않으며 끝나고 최소 간격이 지나면 다시 넘길 수 있다")
    void runsIndexingUnderLockAndDoesNotQueueWhilePending() {

        when(lock.runExclusively(any())).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return true;
        });
        List<Runnable> queued = new ArrayList<>();
        JobPostingReindexTrigger trigger = new JobPostingReindexTrigger(indexing, lock, clock, queued::add);

        trigger.request();
        trigger.request();
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        now.set(now.get().plusSeconds(600));
        trigger.request();
        assertThat(queued).hasSize(2);
        verify(indexing, times(1)).indexAll();

    }

    @Test
    @DisplayName("공고 가져오기나 다른 색인이 잠금을 쥐고 있으면 색인을 보내지 않고, 시작하지 못한 요청은 시각을 남기지 않아 다음 요청이 바로 다시 시도한다")
    void skipsWhenLockIsHeldElsewhere() {

        when(lock.runExclusively(any())).thenReturn(false);
        JobPostingReindexTrigger trigger = new JobPostingReindexTrigger(indexing, lock, clock, Runnable::run);

        trigger.request();
        trigger.request();

        verify(lock, times(2)).runExclusively(any());
        verify(indexing, never()).indexAll();

    }

    @Test
    @DisplayName("색인이 오래 걸려도 마지막으로 시작한 때부터 10분이 안 지났으면 보내지 않고, 시작한 때부터 10분이 지나면 다시 보낸다")
    void skipsWithinMinimumIntervalAndRestartsAfterIt(CapturedOutput output) {

        when(lock.runExclusively(any())).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return true;
        });
        Instant start = now.get();
        doAnswer(call -> {
            now.set(now.get().plusSeconds(300));
            return null;
        }).when(indexing).indexAll();
        JobPostingReindexTrigger trigger = new JobPostingReindexTrigger(indexing, lock, clock, Runnable::run);

        trigger.request();
        now.set(start.plusSeconds(599));
        trigger.request();
        verify(indexing, times(1)).indexAll();
        assertThat(output.getOut()).contains("공고 색인 다시 보내기를 건너뜀: 마지막 시작");

        now.set(start.plusSeconds(600));
        trigger.request();
        verify(indexing, times(2)).indexAll();

    }
}
