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

    @Autowired
    public RepoRecommendationWorker(RepoRecommendationRepository recommendations, RepoRecommendationService service,
                                    Clock clock) {
        this(recommendations, service, clock, Executors.newSingleThreadExecutor());
    }

    RepoRecommendationWorker(RepoRecommendationRepository recommendations, RepoRecommendationService service,
                             Clock clock, Executor executor) {
        this.recommendations = recommendations;
        this.service = service;
        this.clock = clock;
        this.executor = executor;
    }

    // 앞 차례가 아직 계산 중이면 새로 넘기지 않아, 같은 레포를 두 곳에서 계산하지 않는다
    @Scheduled(fixedDelay = 15_000)
    public void work() {

        if (running.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
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
