package com.gitory.backend.job.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.analysis.grouping")
public record JobGroupingProperties(boolean enabled) {
}
