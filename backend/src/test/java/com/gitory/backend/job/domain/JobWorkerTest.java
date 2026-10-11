package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobWorkerTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JobWorker.class)
            .withBean(JobRunner.class, () -> mock(JobRunner.class));

    @Test
    @DisplayName("설정으로 워커를 끄면 워커가 만들어지지 않는다")
    void workerIsOffWhenDisabled() {

        context.withPropertyValues("gitory.job.worker.enabled=false")
                .run(started -> assertThat(started).doesNotHaveBean(JobWorker.class));

    }

    @Test
    @DisplayName("설정으로 워커를 켜면 워커가 만들어진다")
    void workerIsOnWhenEnabled() {

        context.withPropertyValues("gitory.job.worker.enabled=true")
                .run(started -> assertThat(started).hasSingleBean(JobWorker.class));

    }
}
