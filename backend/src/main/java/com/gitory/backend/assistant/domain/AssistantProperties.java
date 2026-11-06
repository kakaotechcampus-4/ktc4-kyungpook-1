package com.gitory.backend.assistant.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.assistant")
public record AssistantProperties(int stepLimit) {

    // AI 계약이 남은 걸음 수를 10까지만 받는다
    public AssistantProperties {
        if (stepLimit < 1 || stepLimit > 10) {
            throw new IllegalArgumentException("gitory.assistant.step-limit 는 1 이상 10 이하여야 한다");
        }
    }
}
