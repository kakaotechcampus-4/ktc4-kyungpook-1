package com.gitory.backend.recommend.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.analysis.grouping")
public record CandidateGroupingProperties(int version) {
}
