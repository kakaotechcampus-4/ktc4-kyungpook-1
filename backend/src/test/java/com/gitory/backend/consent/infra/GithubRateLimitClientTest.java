package com.gitory.backend.consent.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(GithubRateLimitClient.class)
class GithubRateLimitClientTest {

    private static final String TOKEN = "gho_test";
    private static final String URL = "https://api.github.com/rate_limit";

    @Autowired
    GithubRateLimitClient client;

    @Autowired
    MockRestServiceServer server;

    @Test
    @DisplayName("GET /rate_limit 에 토큰과 API 버전 헤더를 담아 묻는다")
    void asksRateLimitWithBearerToken() {

        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(respondWith(0, "1893456000"));

        client.resetAt(TOKEN);

        server.verify();

    }

    @Test
    @DisplayName("core 한도가 0 이면 reset 을 풀리는 시각으로 돌려준다")
    void returnsResetWhenNothingRemains() {

        server.expect(requestTo(URL)).andRespond(respondWith(0, "1893456000"));

        assertThat(client.resetAt(TOKEN)).contains(Instant.ofEpochSecond(1893456000L));

    }

    @Test
    @DisplayName("core 한도가 남아 있으면 조회한 시각에서 60초 뒤를 돌려준다")
    void returnsOneMinuteLaterWhenSomethingRemains() {

        server.expect(requestTo(URL)).andRespond(respondWith(4821, "1893456000"));
        Instant before = Instant.now();

        Optional<Instant> resetAt = client.resetAt(TOKEN);

        assertThat(resetAt).isPresent();
        assertThat(resetAt.get()).isBetween(before.plusSeconds(60), Instant.now().plusSeconds(60));

    }

    @Test
    @DisplayName("한도가 남아 있으면 reset 칸이 이상해도 조회한 시각에서 60초 뒤를 돌려준다")
    void ignoresResetWhenSomethingRemains() {

        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"resources\": {\"core\": {\"remaining\": 10}}}", MediaType.APPLICATION_JSON));

        assertThat(client.resetAt(TOKEN)).isPresent();

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"resources\": {}}",
            "{\"resources\": {\"core\": {\"remaining\": 0}}}",
            "{\"resources\": {\"core\": {\"remaining\": \"0\", \"reset\": 1893456000}}}",
            "{\"resources\": {\"core\": {\"remaining\": 0, \"reset\": \"soon\"}}}",
            "{\"resources\": {\"core\": {\"remaining\": null, \"reset\": 1893456000}}}",
            "{\"resources\": {\"search\": {\"remaining\": 0, \"reset\": 1893456000}}}"
    })
    @DisplayName("core 의 remaining·reset 칸이 없거나 숫자가 아니면 빈 값을 돌려준다")
    void returnsEmptyForUnusableBody(String body) {

        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.resetAt(TOKEN)).isEmpty();

    }

    @Test
    @DisplayName("GitHub 이 오류로 답하면 예외를 그대로 던진다")
    void passesThroughHttpFailure() {

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.resetAt(TOKEN)).isInstanceOf(HttpClientErrorException.class);

    }

    private static ResponseCreator respondWith(int remaining, String reset) {

        return withSuccess("""
                {"resources": {"core": {"limit": 5000, "remaining": %d, "reset": %s},
                               "search": {"limit": 30, "remaining": 30, "reset": 1}}}
                """.formatted(remaining, reset), MediaType.APPLICATION_JSON);

    }
}
