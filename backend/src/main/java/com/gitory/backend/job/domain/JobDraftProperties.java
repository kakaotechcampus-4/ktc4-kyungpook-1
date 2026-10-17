package com.gitory.backend.job.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.cards.ai-draft")
public record JobDraftProperties(boolean enabled) {
}
