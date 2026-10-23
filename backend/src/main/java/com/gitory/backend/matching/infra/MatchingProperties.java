package com.gitory.backend.matching.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("gitory.matching")
public record MatchingProperties(List<CompanySource> sources, String greenhouseBaseUrl) {

    public MatchingProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
        greenhouseBaseUrl = greenhouseBaseUrl == null ? "https://boards-api.greenhouse.io" : greenhouseBaseUrl;
    }
}
