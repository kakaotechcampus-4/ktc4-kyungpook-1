package com.gitory.backend.consent.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gitory.github")
public record GithubApiProperties(String apiBaseUrl) {

    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";

    public GithubApiProperties {
        apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API_BASE_URL : apiBaseUrl.strip();
        while (apiBaseUrl.endsWith("/")) {
            apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
        }
    }
}
