package com.gitory.backend.job.domain;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 2초마다 멈춘 Job 을 정리하고, 빈 자리만큼 대기 중인 Job 을 꺼내 실행 스레드에 넘긴다
 * 꺼내기는 이 스케줄러 스레드 하나만 한다 — 실행 스레드가 각자 꺼내면 커밋 전의 서로의 RUNNING 을 못 봐 같은 사용자의 Job 이 둘 돈다
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@EnableConfigurationProperties(JobWorkerProperties.class)
@ConditionalOnProperty(name = "gitory.job.worker.enabled", havingValue = "true")
public class JobWorker {

    private final JobRunner runner;
    private final JobWorkerProperties properties;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger runningJobs = new AtomicInteger();

    // 스케줄러는 컨텍스트가 다 뜬 뒤에 돌기 시작하므로, 빈을 만들 때 되돌리면 첫 꺼내기보다 항상 먼저다
    @PostConstruct
    void requeueInterruptedJobs() {

        runner.requeueRunningJobs();

    }

    @Scheduled(fixedDelay = 2000)
    public void work() {

        runner.failStuckJobs();

        while (runningJobs.get() < properties.concurrency()) {
            Optional<AnalysisJob> claimed = runner.claimNext();
            if (claimed.isEmpty()) {
                return;
            }

            runningJobs.incrementAndGet();
            pool.execute(() -> runAndFreeSlot(claimed.get()));
        }

    }

    private void runAndFreeSlot(AnalysisJob job) {

        try {
            runner.run(job);
        } finally {
            runningJobs.decrementAndGet();
        }

    }
}
