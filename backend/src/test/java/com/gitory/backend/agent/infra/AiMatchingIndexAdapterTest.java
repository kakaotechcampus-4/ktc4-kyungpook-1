package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.IndexCriterion;
import com.gitory.backend.agent.port.IndexPosting;
import com.gitory.backend.agent.port.IndexPostingStatus;
import com.gitory.backend.agent.port.MatchingIndexResult;
import com.gitory.backend.agent.port.MatchingIndexUnavailableException;
import com.gitory.backend.agent.port.MatchingPruneResult;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 HTTP 를 거쳐 공고 색인과 색인 정리 API 의 요청 모양, 응답 검사, 실패 구분을 확인한다 */
class AiMatchingIndexAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String RESPONSE_TEXT = "response-only-text";

    private HttpServer server;
    private AiMatchingIndexAdapter adapter;
    private volatile String responseBody;
    private volatile int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        responseBody = success(MAPPER.createObjectNode().put("indexed_postings", 2).put("indexed_criteria", 3)
                .put("deactivated_postings", 0));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        adapter = adapterOf(properties().build());

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("색인 요청은 지우기 칸 없이 공고 id와 상태와 조건 id·종류·글만 계약 모양 그대로 보내고 개수 세 가지를 읽는다")
    void sendsIndexRequestWithoutReplaceAll() {

        MatchingIndexResult result = adapter.index(List.of(new IndexPosting(7, IndexPostingStatus.ACTIVE,
                List.of(new IndexCriterion(11, PostingCriterionType.REQUIRED, "Java 개발 경험")))));

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo("/internal/matching/postings/index");
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {"postings": [{"posting_id": 7, "status": "ACTIVE",
                  "criteria": [{"criterion_id": 11, "criterion_type": "REQUIRED", "text": "Java 개발 경험"}]}]}
                """));
        assertThat(result).isEqualTo(new MatchingIndexResult(2, 3, 0));

    }

    @Test
    @DisplayName("정리 요청은 남길 공고 id 목록만 보내고 남긴 수와 지운 수를 읽는다")
    void sendsPruneRequestWithKeepIds() {

        responseBody = success(MAPPER.createObjectNode().put("kept_postings", 2).put("removed_postings", 5));

        MatchingPruneResult result = adapter.prune(List.of(7L, 9L));

        assertThat(requestPath).isEqualTo("/internal/matching/postings/prune");
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("{\"keep_posting_ids\": [7, 9]}"));
        assertThat(result).isEqualTo(new MatchingPruneResult(2, 5));

    }

    @ParameterizedTest
    @CsvSource({"503, LLM_UNAVAILABLE, true", "400, INVALID_PAYLOAD, false", "500, INTERNAL_ERROR, false"})
    @DisplayName("AI 오류는 코드와 다시 시도 여부를 담은 예외로 끝나고 응답의 글은 어디에도 남기지 않는다")
    void classifiesFailure(int status, String code, boolean retryable) {

        responseStatus = status;
        responseBody = failure(code, retryable);

        assertThatThrownBy(() -> adapter.prune(List.of(7L)))
                .isInstanceOfSatisfying(MatchingIndexUnavailableException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(code);
                    assertThat(failure.isRetryable()).isEqualTo(retryable);
                    for (Throwable chain = failure; chain != null; chain = chain.getCause()) {
                        assertThat(String.valueOf(chain.getMessage())).doesNotContain(RESPONSE_TEXT);
                    }
                });

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"indexed_postings\": -1, \"indexed_criteria\": 3, \"deactivated_postings\": 0}",
            "{\"indexed_postings\": \"2\", \"indexed_criteria\": 3, \"deactivated_postings\": 0}",
            "{\"indexed_postings\": 2, \"indexed_criteria\": 3}"})
    @DisplayName("개수가 음수이거나 정수가 아니거나 빠진 응답은 다시 시도할 수 없는 이상한 응답으로 끝난다")
    void rejectsMalformedCounts(String data) {

        responseBody = success((ObjectNode) MAPPER.readTree(data));

        assertThatThrownBy(() -> adapter.index(List.of()))
                .isInstanceOfSatisfying(MatchingIndexUnavailableException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo("AI_INVALID_RESPONSE");
                    assertThat(failure.isInvalidResponse()).isTrue();
                    assertThat(failure.isRetryable()).isFalse();
                });

    }

    @Test
    @DisplayName("색인 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다")
    void timesOutByMatchingIndexReadTimeout() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().matchingIndexReadTimeout(Duration.ofMillis(100)).build());

        assertThatThrownBy(() -> adapter.prune(List.of(7L)))
                .isInstanceOfSatisfying(MatchingIndexUnavailableException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(failure.isRetryable()).isTrue();
                });

    }

    private static String success(ObjectNode data) {

        ObjectNode envelope = MAPPER.createObjectNode().put("success", true);
        envelope.set("data", data);
        envelope.set("meta", MAPPER.createObjectNode().putNull("model").put("tool_calls_made", 0)
                .put("processing_ms", 3));
        envelope.putNull("error");
        return MAPPER.writeValueAsString(envelope);

    }

    private static String failure(String code, boolean retryable) {

        ObjectNode envelope = MAPPER.createObjectNode().put("success", false).putNull("data");
        envelope.set("meta", MAPPER.createObjectNode());
        envelope.set("error", MAPPER.createObjectNode().put("code", code).put("message", RESPONSE_TEXT)
                .put("retryable", retryable));
        return MAPPER.writeValueAsString(envelope);

    }

    private AiTestProperties properties() {

        return AiTestProperties.defaults().baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

    }

    private AiMatchingIndexAdapter adapterOf(AiClientProperties properties) {

        return new AiMatchingIndexAdapter(new AiHttpClient(properties, MAPPER), properties);

    }
}
