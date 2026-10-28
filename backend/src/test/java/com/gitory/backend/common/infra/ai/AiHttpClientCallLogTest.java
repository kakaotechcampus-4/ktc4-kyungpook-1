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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class AiHttpClientCallLogTest {

    private static final String GROUPS = "/internal/analysis/groups";
    private static final Map<String, Object> REQUEST = Map.of("user_repository_id", 1);
    private static final String META = "{\"model\":\"m\",\"llm_calls\":2,\"input_tokens\":30,\"output_tokens\":7,"
            + "\"ended_by\":\"COMPLETED\",\"processing_ms\":120}";

    private final List<AiCall> calls = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private AiHttpClient client;
    private volatile int responseStatus = 200;
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws Exception {

        responseBody = "{\"success\":true,\"data\":{\"candidates\":[]},\"meta\":" + META + ",\"error\":null}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        client = clientRecordingWith(calls::add);

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("응답 형식이 있는 AI 호출은 경로·상태와 AI 가 준 처리 기록을 담아 한 번 남긴다")
    void recordsPostWithAiMeta() {

        JsonNode data = client.post(GROUPS, REQUEST, Duration.ofSeconds(5));

        assertThat(data.path("candidates").isArray()).isTrue();
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo(GROUPS);
            assertThat(call.httpStatus()).isEqualTo(200);
            assertThat(call.errorCode()).isNull();
            assertThat(call.meta().path("llm_calls").asInt()).isEqualTo(2);
            assertThat(call.elapsedMs()).isNotNegative();
        });

    }

    @Test
    @DisplayName("실패한 AI 호출도 오류 코드와 AI 가 준 처리 기록을 담아 남기고, 부른 쪽에는 같은 오류를 던진다")
    void recordsFailedPostWithErrorCodeAndMeta() {

        responseStatus = 503;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":" + META + ","
                + "\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}";

        assertThatThrownBy(() -> client.post(GROUPS, REQUEST, Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(AiClientException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("LLM_UNAVAILABLE"));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.httpStatus()).isEqualTo(503);
            assertThat(call.errorCode()).isEqualTo("LLM_UNAVAILABLE");
            assertThat(call.meta().path("llm_calls").asInt()).isEqualTo(2);
        });

    }

    @Test
    @DisplayName("AI 에 닿지 못하면 처리 기록 없이 상태도 비운 채 AI_UNAVAILABLE 로 남긴다")
    void recordsUnreachableCallWithoutMeta() {

        server.stop(0);

        assertThatThrownBy(() -> client.post(GROUPS, REQUEST, Duration.ofSeconds(5)))
                .isInstanceOf(AiClientException.class);
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.httpStatus()).isNull();
            assertThat(call.errorCode()).isEqualTo("AI_UNAVAILABLE");
            assertThat(call.meta()).isNull();
        });

    }

    @Test
    @DisplayName("진행 스트리밍 호출도 진행 줄이 아니라 마지막 결과 줄의 상태·처리 기록으로 한 번 남기고, 결과 줄이 실패면 그 오류 코드를 남긴다")
    void recordsStreamingCallByItsResultLine() {

        List<AiProgress> progress = new CopyOnWriteArrayList<>();
        responseBody = "{\"type\":\"progress\",\"step\":\"PR_REVIEW\",\"done\":1,\"total\":2}\n"
                + "{\"type\":\"result\",\"status\":200,\"body\":{\"success\":true,\"data\":{\"commits\":[]},\"meta\":"
                + META + ",\"error\":null}}\n";
        client.collectStreaming(REQUEST, "ghp_" + "x".repeat(36), progress::add);
        responseBody = "{\"type\":\"result\",\"status\":401,\"body\":{\"success\":false,\"data\":null,\"meta\":" + META
                + ",\"error\":{\"code\":\"GITHUB_UNAUTHORIZED\",\"message\":\"m\",\"retryable\":false}}}\n";
        assertThatThrownBy(() -> client.postStreaming(GROUPS + "/stream", REQUEST, Duration.ofSeconds(5), progress::add))
                .isInstanceOf(AiClientException.class);

        assertThat(progress).hasSize(1);
        assertThat(calls).extracting(AiCall::path, AiCall::httpStatus, AiCall::errorCode)
                .containsExactly(tuple("/internal/collect/stream", 200, null),
                        tuple(GROUPS + "/stream", 401, "GITHUB_UNAUTHORIZED"));
        assertThat(calls).allSatisfy(call -> assertThat(call.meta().path("llm_calls").asInt()).isEqualTo(2));

    }

    @Test
    @DisplayName("상태 확인·문서 조회(GET)는 남기지 않는다")
    void doesNotRecordGet() {

        responseBody = "{\"status\":\"ok\"}";

        client.get("/health");

        assertThat(calls).isEmpty();

    }

    @Test
    @DisplayName("기록을 남기다 실패해도 AI 호출은 받은 결과를 그대로 돌려주고, 실패한 호출은 AI 의 오류를 그대로 던진다")
    void recorderFailureDoesNotChangeTheResult() {

        AiHttpClient failingRecorder = clientRecordingWith(call -> {
            throw new IllegalStateException("기록 실패");
        });

        assertThat(failingRecorder.post(GROUPS, REQUEST, Duration.ofSeconds(5)).path("candidates").isArray()).isTrue();
        responseStatus = 503;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":{},"
                + "\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}";
        assertThatThrownBy(() -> failingRecorder.post(GROUPS, REQUEST, Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(AiClientException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("LLM_UNAVAILABLE"));

    }

    @Test
    @DisplayName("스레드에 걸어 둔 Job·카드를 기록에 함께 남기고, 그 일을 벗어난 호출에는 남기지 않는다")
    void recordsJobAndCardOfTheThread() {

        AiCallContext.runForJob(7L, () -> AiCallContext.callForCard(9L,
                () -> client.post(GROUPS, REQUEST, Duration.ofSeconds(5))));
        client.post(GROUPS, REQUEST, Duration.ofSeconds(5));

        assertThat(calls).extracting(AiCall::jobId, AiCall::cardId)
                .containsExactly(tuple(7L, 9L), tuple(null, null));

    }

    private AiHttpClient clientRecordingWith(AiCallRecorder recorder) {

        return new AiHttpClient(AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build(),
                JsonMapper.builder().build(), recorder);

    }
}
