package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class JobWorkerTest {

    private final JobRunner runner = mock(JobRunner.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JobWorker.class)
            .withBean(JobRunner.class, () -> runner);

    @Test
    @DisplayName("설정으로 워커를 끄면 워커가 만들어지지 않는다")
    void workerIsOffWhenDisabled() {

        context.withPropertyValues("gitory.job.worker.enabled=false")
                .run(started -> assertThat(started).doesNotHaveBean(JobWorker.class));

    }

    @Test
    @DisplayName("설정으로 워커를 켜면 워커가 만들어진다")
    void workerIsOnWhenEnabled() {

        context.withPropertyValues("gitory.job.worker.enabled=true", "gitory.job.worker.concurrency=3")
                .run(started -> assertThat(started).hasSingleBean(JobWorker.class));

    }

    @Test
    @DisplayName("워커를 켰는데 동시 실행 수가 1 보다 작으면 서버가 뜨지 않는다")
    void rejectsConcurrencyBelowOne() {

        context.withPropertyValues("gitory.job.worker.enabled=true", "gitory.job.worker.concurrency=0")
                .run(started -> assertThat(started).hasFailed());

    }

    @Test
    @DisplayName("워커는 뜰 때 Job 을 꺼내기 전에 남아 있던 RUNNING Job 을 한 번 되돌린다")
    void requeuesRunningJobsBeforeFirstClaim() {

        List<String> calls = new CopyOnWriteArrayList<>();
        willAnswer(invocation -> calls.add("requeue")).given(runner).requeueRunningJobs();
        given(runner.claimNext()).willAnswer(invocation -> {
            calls.add("claim");
            return Optional.empty();
        });

        context.withPropertyValues("gitory.job.worker.enabled=true", "gitory.job.worker.concurrency=3")
                .run(started -> {
                    verify(runner, timeout(5000).atLeastOnce()).claimNext();
                    assertThat(calls).startsWith("requeue", "claim").containsOnlyOnce("requeue");
                });

    }

    @Test
    @DisplayName("실행 중인 Job 이 동시 실행 수에 이르면 대기 중인 Job 이 남아 있어도 더 꺼내지 않는다")
    void claimsNoMoreThanConcurrency() {

        CountDownLatch release = new CountDownLatch(1);
        given(runner.claimNext()).willAnswer(invocation -> Optional.of(job()));
        willAnswer(invocation -> release.await(5, TimeUnit.SECONDS)).given(runner).run(any());
        JobWorker worker = new JobWorker(runner, new JobWorkerProperties(3));

        try {
            worker.work();
            worker.work();

            verify(runner, times(3)).claimNext();
        } finally {
            release.countDown();
        }

    }

    @Test
    @DisplayName("실행 중인 Job 이 끝나면 그 자리만큼 다음 차례에 다시 꺼낸다")
    void claimsAgainAfterJobFinishes() {

        AtomicInteger claims = new AtomicInteger();
        given(runner.claimNext()).willAnswer(invocation -> {
            claims.incrementAndGet();
            return Optional.of(job());
        });
        JobWorker worker = new JobWorker(runner, new JobWorkerProperties(1));

        worker.work();

        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            worker.work();
            return claims.get() >= 2;
        });

    }

    @Test
    @DisplayName("Job 실행이 예외로 끝나도 그 자리를 돌려받아 다음 차례에 다시 꺼낸다")
    void claimsAgainAfterRunThrows() {

        AtomicInteger claims = new AtomicInteger();
        given(runner.claimNext()).willAnswer(invocation -> {
            claims.incrementAndGet();
            return Optional.of(job());
        });
        willThrow(new IllegalStateException("Job 을 실패로 끝내지 못했다")).given(runner).run(any());
        JobWorker worker = new JobWorker(runner, new JobWorkerProperties(1));

        worker.work();

        await().dontCatchUncaughtExceptions().atMost(5, TimeUnit.SECONDS).until(() -> {
            worker.work();
            return claims.get() >= 2;
        });

    }

    @Test
    @DisplayName("꺼낼 Job 이 없으면 빈 자리가 남아도 더 묻지 않는다")
    void stopsWhenNothingToClaim() {

        given(runner.claimNext()).willReturn(Optional.empty());

        new JobWorker(runner, new JobWorkerProperties(3)).work();

        verify(runner, times(1)).claimNext();
        verify(runner, never()).run(any());

    }

    private static AnalysisJob job() {

        return AnalysisJob.enqueue(1L, 10L, "11111111-1111-4111-8111-111111111111");

    }
}
