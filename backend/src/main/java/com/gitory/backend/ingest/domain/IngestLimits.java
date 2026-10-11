package com.gitory.backend.ingest.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.ingest")
public record IngestLimits(int maxCommits, int maxPullRequests) {
}
