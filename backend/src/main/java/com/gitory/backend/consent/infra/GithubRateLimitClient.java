package com.gitory.backend.consent.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** GitHub 에 토큰 주인의 REST 호출 한도가 풀리는 시각을 묻는다 */
@Component
@EnableConfigurationProperties(GithubApiProperties.class)
public class GithubRateLimitClient {

    // 한도가 남았는데도 막혔다면 이 조회에 보이지 않는 2차 한도라 풀리는 시각을 알 수 없어 이만큼만 기다리게 한다
    private static final Duration SECONDARY_LIMIT_WAIT = Duration.ofSeconds(60);

    private final RestClient restClient;

    public GithubRateLimitClient(RestClient.Builder builder, GithubApiProperties properties) {
        this.restClient = builder
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** 응답에 한도 칸이 없거나 숫자가 아니면 빈 값을 돌려주고, 요청이 실패하면 예외가 그대로 난다 */
    Optional<Instant> resetAt(String token) {

        JsonNode response = restClient.get()
                .uri("/rate_limit")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
        Instant askedAt = Instant.now();
        if (response == null) {
            return Optional.empty();
        }

        JsonNode core = response.path("resources").path("core");
        JsonNode remaining = core.path("remaining");
        JsonNode reset = core.path("reset");
        if (!remaining.isIntegralNumber()) {
            return Optional.empty();
        }

        if (remaining.asLong() > 0) {
            return Optional.of(askedAt.plus(SECONDARY_LIMIT_WAIT));
        }

        if (!reset.isIntegralNumber()) {
            return Optional.empty();
        }

        return Optional.of(Instant.ofEpochSecond(reset.asLong()));

    }
}
