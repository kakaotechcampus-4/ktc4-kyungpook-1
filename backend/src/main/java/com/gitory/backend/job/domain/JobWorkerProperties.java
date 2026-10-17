package com.gitory.backend.job.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.job.worker")
public record JobWorkerProperties(int concurrency) {

    public JobWorkerProperties {

        if (concurrency < 1) {
            throw new IllegalArgumentException("gitory.job.worker.concurrency 는 1 이상이어야 한다");
        }

    }
}
