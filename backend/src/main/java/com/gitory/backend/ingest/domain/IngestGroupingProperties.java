package com.gitory.backend.ingest.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.analysis.grouping")
public record IngestGroupingProperties(boolean enabled) {
}
