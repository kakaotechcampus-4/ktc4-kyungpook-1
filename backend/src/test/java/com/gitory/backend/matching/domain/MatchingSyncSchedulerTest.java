package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.JobPostingSourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchingSyncSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");

    private final JobPostingSyncService sync = mock(JobPostingSyncService.class);
    private final JobPostingSourceRepository sources = mock(JobPostingSourceRepository.class);

    @ParameterizedTest(name = "마지막 시도 {0}시간 전 → 실행 {1}")
    @DisplayName("서버가 뜰 때 마지막 시도가 없거나 24시간보다 오래됐으면 곧바로 한 번 가져온다")
    @CsvSource({"none, true", "25, true", "24, false", "23, false"})
    void catchesUpOnStartupWhenLastAttemptIsOld(String hoursAgo, boolean runs) {

        when(sources.findLatestAttemptAt())
                .thenReturn("none".equals(hoursAgo) ? null : NOW.minus(Duration.ofHours(Long.parseLong(hoursAgo))));
        MatchingSyncScheduler scheduler = new MatchingSyncScheduler(sync, sources, Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run, null);

        scheduler.catchUp();

        if (runs) {
            verify(sync).sync();
        } else {
            verify(sync, never()).sync();
        }

    }

    @Test
    @DisplayName("이미 돌고 있거나 대기 중이면 새로 넘기지 않고 끝나면 다시 넘길 수 있다")
    void doesNotQueueWhileAlreadyPending() {

        List<Runnable> queued = new ArrayList<>();
        MatchingSyncScheduler scheduler = new MatchingSyncScheduler(sync, sources, Clock.fixed(NOW, ZoneOffset.UTC),
                queued::add, null);

        scheduler.scheduled();
        scheduler.scheduled();
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        scheduler.scheduled();
        assertThat(queued).hasSize(2);
        verify(sync, times(1)).sync();

    }

    @Test
    @DisplayName("공고 가져오기가 끝나면 같은 스레드에서 이어서 새 공고 알림 확인을 돌고, 가져오기가 예외로 끝나면 돌지 않는다")
    void runsNewPostingAlertsAfterTheSyncOnlyWhenTheSyncFinished() {

        NewPostingAlertService alerts = mock(NewPostingAlertService.class);
        MatchingSyncScheduler scheduler = new MatchingSyncScheduler(sync, sources, Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run, alerts);

        scheduler.scheduled();

        InOrder order = inOrder(sync, alerts);
        order.verify(sync).sync();
        order.verify(alerts).run();

        doThrow(new IllegalStateException("가져오기 실패")).when(sync).sync();
        scheduler.scheduled();

        verify(alerts, times(1)).run();

    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 일정 빈을 만들지 않고 켜면 만든다")
    void createsSchedulerOnlyWhenSwitchIsOn() {

        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(MatchingSyncScheduler.class)
                .withBean(JobPostingSyncService.class, () -> sync)
                .withBean(JobPostingSourceRepository.class, () -> sources)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));

        runner.withPropertyValues("gitory.matching.sync.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MatchingSyncScheduler.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(MatchingSyncScheduler.class));
        runner.withPropertyValues("gitory.matching.sync.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MatchingSyncScheduler.class));

    }
}
