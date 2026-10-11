package com.gitory.backend.job.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 2초마다 멈춘 Job 을 정리하고 대기 중인 Job 하나를 실행한다 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gitory.job.worker.enabled", havingValue = "true")
public class JobWorker {

    private final JobRunner runner;

    @Scheduled(fixedDelay = 2000)
    public void work() {

        runner.failStuckJobs();
        runner.runNext();

    }
}
