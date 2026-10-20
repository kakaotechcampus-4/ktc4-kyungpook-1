package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.JobPostingSourceRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 하루 한 번 새벽에 공고 가져오기를 시작하고, 서버가 꺼져 있어 놓쳤으면 뜰 때 곧바로 한 번 한다
 * 가져오기는 회사마다 1초씩 띄워 요청해 오래 걸리므로 스케줄러 스레드를 붙잡지 않게 전용 스레드 하나에 넘기고
 * 이미 돌고 있거나 대기 중이면 새로 넘기지 않아 연달아 두 번 돌지 않게 한다
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "gitory.matching.sync.enabled", havingValue = "true")
public class MatchingSyncScheduler {

    private static final Duration CATCH_UP_AFTER = Duration.ofHours(24);

    private final JobPostingSyncService sync;
    private final JobPostingSourceRepository sources;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean pending = new AtomicBoolean();

    @Autowired
    public MatchingSyncScheduler(JobPostingSyncService sync, JobPostingSourceRepository sources, Clock clock) {
        this(sync, sources, clock, Executors.newSingleThreadExecutor());
    }

    MatchingSyncScheduler(JobPostingSyncService sync, JobPostingSourceRepository sources, Clock clock,
                          Executor executor) {
        this.sync = sync;
        this.sources = sources;
        this.clock = clock;
        this.executor = executor;
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void scheduled() {

        submit();

    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {

        Instant lastAttempt = sources.findLatestAttemptAt();
        if (lastAttempt == null || lastAttempt.isBefore(clock.instant().minus(CATCH_UP_AFTER))) {
            submit();
        }

    }

    private void submit() {

        if (!pending.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                sync.sync();
            } catch (RuntimeException e) {
                log.error("공고 가져오기가 예상 못 한 오류로 끝났다", e);
            } finally {
                pending.set(false);
            }
        });

    }

    @PreDestroy
    void stop() throws InterruptedException {

        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
            service.awaitTermination(5, TimeUnit.SECONDS);
        }

    }
}
