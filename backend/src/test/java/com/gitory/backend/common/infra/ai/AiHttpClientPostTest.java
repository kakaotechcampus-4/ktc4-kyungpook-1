package com.gitory.backend.common.infra.ai;

import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiHttpClientPostTest {

    private static final String GROUPS = "/internal/analysis/groups";
    private static final String DIFF_EVIDENCE = "/internal/analysis/diff-evidence";
    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";
    private static final Map<String, Object> REQUEST = Map.of("user_repository_id", 1);

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger calls = new AtomicInteger();

    private HttpServer server;
    private AiHttpClient client;
    private volatile long responseDelayMs;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestToken;
    private volatile String requestContentType;
    private volatile String requestUpgrade;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            requestToken = exchange.getRequestHeaders().getFirst("X-GitHub-Token");
            requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestUpgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] envelope = "{\"success\":true,\"data\":{\"candidates\":[]},\"meta\":{},\"error\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, envelope.length);
            try (var output = exchange.getResponseBody()) {
                output.write(envelope);
            }
        });
        server.start();
        client = new AiHttpClient(AiTestProperties.defaults().baseUrl(baseUrl()).build(), mapper);

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("토큰 없이 JSON 본문을 보내고 성공 봉투의 data 만 돌려준다")
    void postsWithoutTokenAndReturnsData() {

        JsonNode data = client.post(GROUPS, REQUEST, Duration.ofSeconds(2));

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo(GROUPS);
        assertThat(requestToken).isNull();
        assertThat(requestContentType).startsWith("application/json");
        assertThat(requestBody).contains("\"user_repository_id\"");
        assertThat(data.has("candidates")).isTrue();
        assertThat(data.has("success")).isFalse();

    }

    @Test
    @DisplayName("HTTP/2 업그레이드 표시 없이 본문을 그대로 보낸다")
    void postsWithoutHttp2Upgrade() {

        client.post(GROUPS, REQUEST, Duration.ofSeconds(2));

        assertThat(calls).hasValue(1);
        assertThat(requestUpgrade).isNull();
        assertThat(requestBody).contains("\"user_repository_id\"");

    }

    @Test
    @DisplayName("응답 대기 시간은 설정 값이 아니라 호출마다 넘긴 값을 쓴다")
    void waitsForTheTimeoutOfEachCall() {

        responseDelayMs = 300;
        AiHttpClient shortSettings = new AiHttpClient(AiTestProperties.defaults().baseUrl(baseUrl())
                .readTimeout(Duration.ofMillis(50)).collectReadTimeout(Duration.ofMillis(50))
                .groupsReadTimeout(Duration.ofMillis(50)).interviewReadTimeout(Duration.ofMillis(50)).build(), mapper);

        assertThat(shortSettings.post(GROUPS, REQUEST, Duration.ofSeconds(3)).has("candidates")).isTrue();
        assertThatThrownBy(() -> shortSettings.post(GROUPS, REQUEST, Duration.ofMillis(50)))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                });

    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:1/internal/analysis/groups", "//127.0.0.1:1/internal/analysis/groups",
            "/internal/analysis/groups?debug=true"})
    @DisplayName("내부 절대 경로가 아니면 AI 에 보내기 전에 거절한다")
    void rejectsNonInternalPathBeforeSending(String path) {

        assertThatThrownBy(() -> client.post(path, REQUEST, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);

    }

    @Test
    @DisplayName("토큰이 필요한 호출은 토큰을 X-GitHub-Token 헤더에만 싣고 본문에는 넣지 않는다")
    void postsTokenOnlyInHeader() {

        JsonNode data = client.postWithGithubToken(DIFF_EVIDENCE, REQUEST, TOKEN, Duration.ofSeconds(2));

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo(DIFF_EVIDENCE);
        assertThat(requestToken).isEqualTo(TOKEN);
        assertThat(requestContentType).startsWith("application/json");
        assertThat(requestBody).contains("\"user_repository_id\"").doesNotContain(TOKEN);
        assertThat(data.has("candidates")).isTrue();

    }

    @Test
    @DisplayName("토큰이 필요한 호출도 응답 대기 시간은 설정 값이 아니라 호출마다 넘긴 값을 쓴다")
    void tokenCallWaitsForTheTimeoutOfEachCall() {

        responseDelayMs = 300;
        AiHttpClient shortSettings = new AiHttpClient(AiTestProperties.defaults().baseUrl(baseUrl())
                .readTimeout(Duration.ofMillis(50)).collectReadTimeout(Duration.ofMillis(50))
                .diffEvidenceReadTimeout(Duration.ofMillis(50)).build(), mapper);

        assertThat(shortSettings.postWithGithubToken(DIFF_EVIDENCE, REQUEST, TOKEN, Duration.ofSeconds(3))
                .has("candidates")).isTrue();
        assertThatThrownBy(() -> shortSettings.postWithGithubToken(DIFF_EVIDENCE, REQUEST, TOKEN,
                Duration.ofMillis(50)))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                });

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "gho_abc\r\nX-Injected: 1", "gho_토큰값"})
    @DisplayName("헤더에 넣을 수 없는 토큰은 AI 에 보내기 전에 연결 없음으로 거절하고 예외 메시지에 토큰을 남기지 않는다")
    void rejectsHeaderUnsafeTokenBeforeSending(String token) {

        assertThatThrownBy(() -> client.postWithGithubToken(DIFF_EVIDENCE, REQUEST, token, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("GITHUB_CONNECTION_UNAVAILABLE");
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.getMessage()).doesNotContain("gho_");
                });
        assertThat(calls).hasValue(0);

    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:1/internal/analysis/diff-evidence",
            "//127.0.0.1:1/internal/analysis/diff-evidence", "/internal/analysis/diff-evidence?debug=true"})
    @DisplayName("토큰이 필요한 호출도 내부 절대 경로가 아니면 토큰을 보내기 전에 거절한다")
    void rejectsNonInternalPathBeforeSendingToken(String path) {

        assertThatThrownBy(() -> client.postWithGithubToken(path, REQUEST, TOKEN, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);

    }

    private URI baseUrl() {

        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());

    }
}
