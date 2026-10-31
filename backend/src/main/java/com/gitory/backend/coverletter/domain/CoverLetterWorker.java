package com.gitory.backend.coverletter.domain;

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
 * 2초마다 멈춘 단계를 정리하고, 빈 자리만큼 기다리는 자소서를 집어 실행 스레드에 넘긴다
 * 분석 Job 워커와 자리를 나눠, 오래 걸리는 분석이 자소서를 막지 않는다
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@EnableConfigurationProperties(CoverLetterWorkerProperties.class)
@ConditionalOnProperty(name = "gitory.cover-letter.worker.enabled", havingValue = "true")
public class CoverLetterWorker {

    private final CoverLetterRunner runner;
    private final CoverLetterWorkerProperties properties;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger running = new AtomicInteger();

    // 스케줄러는 컨텍스트가 다 뜬 뒤에 돌기 시작하므로, 빈을 만들 때 되돌리면 첫 집기보다 항상 먼저다
    @PostConstruct
    void requeueInterrupted() {

        runner.requeueRunning();

    }

    @Scheduled(fixedDelay = 2000)
    public void work() {

        runner.failStuck();

        while (running.get() < properties.concurrency()) {
            Optional<CoverLetter> claimed = runner.claimNext();
            if (claimed.isEmpty()) {
                return;
            }

            running.incrementAndGet();
            pool.execute(() -> runAndFreeSlot(claimed.get()));
        }

    }

    private void runAndFreeSlot(CoverLetter letter) {

        try {
            runner.run(letter);
        } finally {
            running.decrementAndGet();
        }

    }
}
