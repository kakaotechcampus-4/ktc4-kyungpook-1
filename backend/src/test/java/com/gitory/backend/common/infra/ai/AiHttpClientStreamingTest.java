package com.gitory.backend.common.infra.ai;

import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiHttpClientStreamingTest {

    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";
    private static final Map<String, Object> REQUEST = Map.of("user_repository_id", 1);
    private static final String OK_RESULT =
            "{\"type\":\"result\",\"status\":200,\"body\":{\"success\":true,\"data\":{\"commits\":[]},\"meta\":{},\"error\":null}}";

    private final List<AiProgress> received = new ArrayList<>();

    private HttpServer server;
    private AiHttpClient client;
    private volatile int responseStatus = 200;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestToken;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestToken = exchange.getRequestHeaders().getFirst("X-GitHub-Token");
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    responseStatus == 200 ? "application/x-ndjson" : "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        client = new AiHttpClient(AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build(),
                JsonMapper.builder().build());

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("수집 스트리밍은 진행 줄을 받을 때마다 넘기고, 마지막 결과 줄의 data 를 스트리밍 없는 수집처럼 돌려준다")
    void passesProgressAndReturnsResultData() {

        responseBody = lines(
                "{\"type\":\"progress\",\"step\":\"PR_REVIEW\",\"done\":0,\"total\":2}",
                "{\"type\":\"progress\",\"step\":\"PR_REVIEW\",\"done\":2,\"total\":2}",
                "{\"type\":\"progress\",\"step\":\"COMMITS\",\"done\":0,\"total\":null}",
                OK_RESULT);

        JsonNode data = client.collectStreaming(REQUEST, TOKEN, received::add);

        assertThat(requestPath).isEqualTo("/internal/collect/stream");
        assertThat(requestToken).isEqualTo(TOKEN);
        assertThat(received).containsExactly(
                new AiProgress("PR_REVIEW", 0, 2),
                new AiProgress("PR_REVIEW", 2, 2),
                new AiProgress("COMMITS", 0, null));
        assertThat(data.path("commits").isArray()).isTrue();

    }

    @Test
    @DisplayName("결과 줄이 실패면 스트리밍 없는 응답의 같은 실패처럼 AI 가 준 오류 코드와 다시 시도 여부로 던진다")
    void failsWithTheResultLineError() {

        responseBody = lines(
                "{\"type\":\"progress\",\"step\":\"PR_REVIEW\",\"done\":0,\"total\":1}",
                "{\"type\":\"result\",\"status\":502,\"body\":{\"success\":false,\"data\":null,\"meta\":{},"
                        + "\"error\":{\"code\":\"GITHUB_API_ERROR\",\"message\":\"x\",\"retryable\":true}}}");

        assertThatThrownBy(() -> client.collectStreaming(REQUEST, TOKEN, received::add))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("GITHUB_API_ERROR");
                    assertThat(error.retryable()).isTrue();
                });

    }

    @Test
    @DisplayName("결과 줄의 상태가 실패인데 본문이 정해진 오류 응답이 아니면 스트리밍 없는 응답처럼 HTTP 상태로 다시 시도 여부를 정한다")
    void failsByStatusWhenResultBodyIsNotErrorResponse() {

        responseBody = lines("{\"type\":\"result\",\"status\":503,\"body\":{\"detail\":\"gateway\"}}");

        assertThatThrownBy(() -> client.collectStreaming(REQUEST, TOKEN, received::add))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_HTTP_ERROR");
                    assertThat(error.retryable()).isTrue();
                });

    }

    @Test
    @DisplayName("진행 줄의 item 은 종류와 값을 그대로 넘기고, 종류가 없는 item 은 버린 채 진행 숫자만 넘긴다")
    void passesItemWithKindAndDropsItemWithoutKind() {

        responseBody = lines(
                "{\"type\":\"progress\",\"step\":\"COMMITS\",\"done\":1,\"total\":3,\"item\":{\"kind\":\"COMMIT\","
                        + "\"sha\":\"abc1234\",\"title\":\"fix: 토큰 만료 처리\",\"files\":3,\"excluded\":\"MERGE_COMMIT\"}}",
                "{\"type\":\"progress\",\"step\":\"COMMITS\",\"done\":2,\"total\":3,\"item\":{\"title\":\"종류 없음\"}}",
                OK_RESULT);

        client.collectStreaming(REQUEST, TOKEN, received::add);

        assertThat(received).containsExactly(
                new AiProgress("COMMITS", 1, 3, new AiProgressItem("COMMIT", null, "fix: 토큰 만료 처리", null, "abc1234", 3,
                        "MERGE_COMMIT", null, null, null)),
                new AiProgress("COMMITS", 2, 3));

    }

    @Test
    @DisplayName("읽을 수 없는 줄과 모양이 틀린 진행 줄은 진행 표시만 빠지도록 버리고 결과 줄까지 읽는다")
    void skipsUnreadableLines() {

        responseBody = lines(
                "not json",
                "{\"type\":\"progress\",\"step\":\"PR_REVIEW\",\"done\":\"many\"}",
                "{\"type\":\"progress\",\"step\":\"COMMITS\",\"done\":1,\"total\":3}",
                OK_RESULT);

        JsonNode data = client.postStreaming("/internal/analysis/groups/stream", REQUEST, Duration.ofSeconds(5),
                received::add);

        assertThat(received).containsExactly(new AiProgress("COMMITS", 1, 3));
        assertThat(data.isObject()).isTrue();

    }

    @Test
    @DisplayName("결과 줄 없이 스트림이 끝나면 끊긴 응답처럼 다시 시도할 수 있는 AI_UNAVAILABLE 로 던진다")
    void failsAsUnavailableWithoutResultLine() {

        responseBody = lines("{\"type\":\"progress\",\"step\":\"PR_REVIEW\",\"done\":0,\"total\":1}");

        assertThatThrownBy(() -> client.collectStreaming(REQUEST, TOKEN, received::add))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                });

    }

    @Test
    @DisplayName("스트림을 열기 전에 실패하면(요청 검증 400) 그 응답의 오류로 던지고 진행은 넘기지 않는다")
    void failsWithErrorBeforeStreamStarts() {

        responseStatus = 400;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":{},"
                + "\"error\":{\"code\":\"INVALID_PAYLOAD\",\"message\":\"x\",\"retryable\":false}}";

        assertThatThrownBy(() -> client.collectStreaming(REQUEST, TOKEN, received::add))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("INVALID_PAYLOAD");
                    assertThat(error.retryable()).isFalse();
                });
        assertThat(received).isEmpty();

    }

    private static String lines(String... lines) {

        return String.join("\n", lines) + "\n";

    }
}
