package com.gitory.backend.job.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.cards")
public record CardCreationProperties(int maxPerRequest) {
}
