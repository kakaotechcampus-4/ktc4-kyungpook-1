package com.gitory.backend.consent.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** GitHub 에 사용자가 우리 앱에 준 권한을 지워 달라고 요청한다 — 지워지면 그 토큰도 GitHub 에서 무효가 된다 */
@Component
public class GithubGrantClient {

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public GithubGrantClient(RestClient.Builder builder,
                             @Value("${spring.security.oauth2.client.registration.github.client-id}") String clientId,
                             @Value("${spring.security.oauth2.client.registration.github.client-secret}") String clientSecret) {
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void deleteGrant(String token) {

        restClient.method(HttpMethod.DELETE)
                .uri("/applications/{clientId}/grant", clientId)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("access_token", token))
                .retrieve()
                .toBodilessEntity();

    }
}
