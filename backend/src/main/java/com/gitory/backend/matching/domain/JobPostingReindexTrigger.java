package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.SyncLock;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 하루 한 번 실행을 기다리지 않고 활성 공고를 색인에 지금 다시 보내게 시작한다
 * 요청 스레드를 붙잡지 않게 전용 스레드 하나에 넘기고, 이미 대기 중이거나 공고 가져오기가 같은 잠금을 쥐고 있으면 새로 돌리지 않는다
 * 고장 난 색인이 요청마다 되풀이되지 않게 마지막으로 시작한 때부터 최소 간격 안에는 다시 시작하지 않는다
 */
@Slf4j
@Component
public class JobPostingReindexTrigger {

    private static final Duration MIN_INTERVAL = Duration.ofMinutes(10);

    private final JobPostingIndexService indexing;
    private final SyncLock lock;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile Instant lastStartedAt;

    @Autowired
    public JobPostingReindexTrigger(JobPostingIndexService indexing, SyncLock lock, Clock clock) {
        this(indexing, lock, clock, Executors.newSingleThreadExecutor());
    }

    JobPostingReindexTrigger(JobPostingIndexService indexing, SyncLock lock, Clock clock, Executor executor) {
        this.indexing = indexing;
        this.lock = lock;
        this.clock = clock;
        this.executor = executor;
    }

    public void request() {

        if (!pending.compareAndSet(false, true)) {
            return;
        }
        Instant last = lastStartedAt;
        if (last != null && clock.instant().isBefore(last.plus(MIN_INTERVAL))) {
            pending.set(false);
            log.info("공고 색인 다시 보내기를 건너뜀: 마지막 시작 {} 부터 {}분이 지나지 않았다 ({} 이후 가능)",
                    last, MIN_INTERVAL.toMinutes(), last.plus(MIN_INTERVAL));
            return;
        }
        executor.execute(() -> {
            try {
                if (!lock.runExclusively(this::indexAndRecordStart)) {
                    log.info("공고 색인 다시 보내기를 건너뜀: 공고 가져오기나 다른 색인 다시 보내기가 이미 실행 중이다");
                }
            } catch (RuntimeException e) {
                log.error("공고 색인 다시 보내기가 예상 못 한 오류로 끝났다", e);
            } finally {
                pending.set(false);
            }
        });

    }

    private void indexAndRecordStart() {

        // 잠금을 못 잡아 돌리지 못한 때는 기록하지 않아야 다음 요청이 간격에 막히지 않고 바로 다시 시도한다
        lastStartedAt = clock.instant();
        indexing.indexAll();

    }

    @PreDestroy
    void stop() throws InterruptedException {

        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
            service.awaitTermination(5, TimeUnit.SECONDS);
        }

    }
}
