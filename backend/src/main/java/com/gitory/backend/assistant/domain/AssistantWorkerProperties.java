package com.gitory.backend.assistant.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.assistant.worker")
public record AssistantWorkerProperties(int concurrency) {

    public AssistantWorkerProperties {
        if (concurrency < 1) {
            throw new IllegalArgumentException("gitory.assistant.worker.concurrency 는 1 이상이어야 한다");
        }
    }
}
