package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.agent.port.MatchingSearchRequest;
import com.gitory.backend.agent.port.MatchingSearchResult;
import com.gitory.backend.agent.port.MatchingSearchUnavailableException;
import com.gitory.backend.agent.port.MatchingStatement;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

/** 실제 HTTP 를 거쳐 매칭 검색 API 의 요청 모양과 응답 읽기, 응답 검사, 실패 구분을 확인한다 */
class AiJobMatchingSearchAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String PATH = "/internal/matching/search";
    private static final String RESPONSE_TEXT = "response-only-text";

    private HttpServer server;
    private AiJobMatchingSearchAdapter adapter;
    private volatile String responseBody;
    private volatile int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        responseBody = success(searchData());
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
    @DisplayName("문장을 statement_id 와 body 로 계약 모양 그대로 보낸다")
    void sendsStatementsInContractShape() {

        adapter.search(request());

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo(PATH);
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {"statements": [
                  {"statement_id": 501, "body": "Spring Boot API의 응답 시간을 개선했습니다."},
                  {"statement_id": 502, "body": "p95 응답 시간이 40% 줄었습니다."}
                ]}
                """));

    }

    @Test
    @DisplayName("응답의 조건·문장 쌍과 색인된 조건 수를 읽는다")
    void readsMatchesAndCollectionSize() {

        MatchingSearchResult result = adapter.search(request());

        assertThat(result).isEqualTo(new MatchingSearchResult(List.of(new CriterionMatch(1001, 501, 0.21),
                new CriterionMatch(1002, 502, 0.3)), 2));

    }

    @Test
    @DisplayName("보내지 않은 statement_id 가 오면 이상한 응답으로 끝난다")
    void rejectsStatementIdThatWasNotSent() {

        responseBody = success(searchData(data -> match(data, 1).put("statement_id", 999)));

        assertFailure(false, "AI_INVALID_RESPONSE");

    }

    @ParameterizedTest
    @MethodSource("malformedResults")
    @DisplayName("계약과 다른 응답은 다시 시도할 수 없는 이상한 응답으로 끝난다")
    void rejectsMalformedResult(ObjectNode data) {

        responseBody = success(data);

        assertFailure(false, "AI_INVALID_RESPONSE");

    }

    @ParameterizedTest
    @CsvSource({"503, LLM_UNAVAILABLE, true, true", "503, LLM_UNAVAILABLE, false, false",
            "400, INVALID_PAYLOAD, false, false", "500, INTERNAL_ERROR, true, false"})
    @DisplayName("AI 가 다시 해도 된다고 알린 실패만 다시 시도할 수 있다")
    void classifiesFailure(int status, String code, boolean envelopeRetryable, boolean retryable) {

        responseStatus = status;
        responseBody = failure(code, envelopeRetryable);

        assertFailure(retryable, code);

    }

    @Test
    @DisplayName("매칭 검색 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다")
    void timesOutByMatchingSearchReadTimeout() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().matchingSearchReadTimeout(Duration.ofMillis(100)).build());

        assertFailure(true, "AI_UNAVAILABLE");

    }

    static Stream<Arguments> malformedResults() {

        return Stream.of(
                argumentSet("matches 가 배열이 아니다", searchData(data -> data.put("matches", "없음"))),
                argumentSet("criterion_id 가 0 이다", searchData(data -> match(data, 0).put("criterion_id", 0))),
                argumentSet("statement_id 가 문자열이다", searchData(data -> match(data, 0).put("statement_id", "501"))),
                argumentSet("distance 가 음수다", searchData(data -> match(data, 0).put("distance", -0.1))),
                argumentSet("distance 가 숫자가 아니다", searchData(data -> match(data, 0).put("distance", "가깝다"))),
                argumentSet("collection_criteria 가 음수다", searchData(data -> data.put("collection_criteria", -1))),
                argumentSet("collection_criteria 가 없다", searchData(data -> data.remove("collection_criteria"))));

    }

    private static ObjectNode match(ObjectNode data, int index) {

        return (ObjectNode) data.path("matches").path(index);

    }

    private static ObjectNode searchData(Consumer<ObjectNode> edit) {

        ObjectNode data = searchData();
        edit.accept(data);
        return data;

    }

    private static ObjectNode searchData() {

        return (ObjectNode) MAPPER.readTree("""
                {
                  "matches": [
                    {"criterion_id": 1001, "statement_id": 501, "distance": 0.21},
                    {"criterion_id": 1002, "statement_id": 502, "distance": 0.3}
                  ],
                  "collection_criteria": 2
                }
                """);

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

    private static MatchingSearchRequest request() {

        return new MatchingSearchRequest(List.of(
                new MatchingStatement(501, "Spring Boot API의 응답 시간을 개선했습니다."),
                new MatchingStatement(502, "p95 응답 시간이 40% 줄었습니다.")));

    }

    private AiTestProperties properties() {

        return AiTestProperties.defaults().baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

    }

    private AiJobMatchingSearchAdapter adapterOf(AiClientProperties properties) {

        return new AiJobMatchingSearchAdapter(new AiHttpClient(properties, MAPPER), properties);

    }

    /** 실패 구분과 오류 코드가 맞고, 응답의 글은 예외 메시지와 원인 어디에도 없다 */
    private void assertFailure(boolean retryable, String code) {

        assertThatThrownBy(() -> adapter.search(request()))
                .isInstanceOfSatisfying(MatchingSearchUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isEqualTo(retryable);
                    assertThat(failure.isInvalidResponse()).isEqualTo(!retryable);
                    assertThat(failure.getErrorCode()).isEqualTo(code);
                    for (Throwable chain = failure; chain != null; chain = chain.getCause()) {
                        assertThat(String.valueOf(chain.getMessage())).doesNotContain(RESPONSE_TEXT);
                    }
                });

    }
}
