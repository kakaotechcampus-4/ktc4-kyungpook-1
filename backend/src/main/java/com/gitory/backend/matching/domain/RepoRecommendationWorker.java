package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.RepoRecommendationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 15초마다 '다시 계산 필요' 표시 뒤 1분 동안 조용한 레포를 찾아 추천을 다시 계산한다
 * 계산은 공고마다 AI 를 불러 몇 분 걸릴 수 있어, 분석 Job 워커가 쓰는 스케줄러 스레드를 붙잡지 않게 전용 스레드 하나에 넘긴다
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "gitory.matching.recommend.worker.enabled", havingValue = "true")
public class RepoRecommendationWorker {

    private final RepoRecommendationRepository recommendations;
    private final RepoRecommendationService service;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean recovered = new AtomicBoolean();

    @Autowired
    public RepoRecommendationWorker(RepoRecommendationRepository recommendations, RepoRecommendationService service,
                                    Clock clock) {
        this(recommendations, service, clock,
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("repo-recommend").factory()));
    }

    RepoRecommendationWorker(RepoRecommendationRepository recommendations, RepoRecommendationService service,
                             Clock clock, Executor executor) {
        this.recommendations = recommendations;
        this.service = service;
        this.clock = clock;
        this.executor = executor;
    }

    // 서버가 1대라 뜬 뒤 첫 차례에는 계산 중인 것이 없어서, 표시 없는 COMPUTING 은 계산하다 끊긴 것이므로 바로 집히게 다시 표시한다
    void reserveInterrupted() {

        int reserved = recommendations.markStaleOfInterrupted(
                Instant.now(clock).minus(RepoRecommendationTrigger.QUIET));
        if (reserved > 0) {
            log.info("계산하다 끊긴 레포 추천 {} 건을 다시 계산하도록 표시한다", reserved);
        }

    }

    // 앞 차례가 아직 계산 중이면 새로 넘기지 않아, 같은 레포를 두 곳에서 계산하지 않는다
    // 끊긴 계산은 첫 차례가 집기 전에 표시해, 이 서버가 방금 집어 계산하는 줄을 끊긴 것으로 잘못 보지 않는다
    @Scheduled(fixedDelay = 15_000)
    public void work() {

        if (running.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    if (!recovered.get()) {
                        reserveInterrupted();
                        recovered.set(true);
                    }
                    drain();
                } finally {
                    running.set(false);
                }
            });
        }

    }

    void drain() {

        Instant cutoff = Instant.now(clock).minus(RepoRecommendationTrigger.QUIET);
        for (RepoRecommendation due : recommendations.findTop20ByStaleSinceLessThanEqualOrderByStaleSinceAsc(cutoff)) {
            if (recommendations.claim(due.getId(), cutoff) == 0) {
                continue;
            }
            try {
                service.compute(due.getUserId(), due.getUserRepositoryId());
            } catch (RuntimeException failure) {
                log.warn("레포 {} 의 추천을 계산하지 못해 실패로 둔다", due.getUserRepositoryId(), failure);
                service.markFailed(due.getUserId(), due.getUserRepositoryId());
            }
        }

    }
}
