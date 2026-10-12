package com.gitory.backend.card.domain.interview;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.interview")
public record InterviewLimits(int maxTurns) {
}
