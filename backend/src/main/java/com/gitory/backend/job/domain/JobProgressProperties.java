package com.gitory.backend.job.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.analysis.live-progress")
public record JobProgressProperties(boolean enabled) {
}
