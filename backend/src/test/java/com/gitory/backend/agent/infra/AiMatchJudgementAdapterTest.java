package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.JudgeCard;
import com.gitory.backend.agent.port.JudgeCondition;
import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.JudgedCondition.ConditionSupport;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.agent.port.PostingCriterionType;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 HTTP 를 거쳐 조건 판단 API 의 요청 모양과 응답 읽기, 응답 검사, 실패 구분을 확인한다 */
class AiMatchJudgementAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private AiMatchJudgementAdapter adapter;
    private volatile String responseBody;
    private volatile int responseStatus = 200;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AiTestProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        adapter = new AiMatchJudgementAdapter(new AiHttpClient(properties.build(), MAPPER));

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("조건·카드를 snake_case 로 보내고, 조건마다 채운 카드와 이유를 읽는다")
    void sendsRequestAndReadsSupports() {

        responseBody = success("""
                {"judgements": [
                  {"condition_id": "11", "met_by": [{"card_id": "1", "reason": "Spring 으로 결제 API 를 만들었다"}]},
                  {"condition_id": "12", "met_by": []}
                ]}""");

        List<JudgedCondition> judged = adapter.judge(request());

        JsonNode sent = MAPPER.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/matching/judgements");
        assertThat(sent.path("company_name").asString()).isEqualTo("에이크미");
        assertThat(sent.path("conditions").get(0).path("criterion_type").asString()).isEqualTo("REQUIRED");
        assertThat(sent.path("cards").get(0).path("card_id").asString()).isEqualTo("1");
        assertThat(sent.path("cards").get(1).has("situation")).isFalse();
        assertThat(judged).containsExactly(
                new JudgedCondition("11", List.of(new ConditionSupport("1", "Spring 으로 결제 API 를 만들었다"))),
                new JudgedCondition("12", List.of()));

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"condition_id\": \"11\", \"met_by\": []}]",
            "[{\"condition_id\": \"11\", \"met_by\": []}, {\"condition_id\": \"11\", \"met_by\": []}]",
            "[{\"condition_id\": \"11\", \"met_by\": []}, {\"condition_id\": \"99\", \"met_by\": []}]",
            "[{\"condition_id\": \"11\", \"met_by\": [{\"card_id\": \"9\", \"reason\": \"이유\"}]}, {\"condition_id\": \"12\", \"met_by\": []}]",
            "[{\"condition_id\": \"11\", \"met_by\": [{\"card_id\": \"1\", \"reason\": \"이유\"}, {\"card_id\": \"1\", \"reason\": \"이유\"}]}, {\"condition_id\": \"12\", \"met_by\": []}]",
            "[{\"condition_id\": \"11\", \"met_by\": [{\"card_id\": \"1\", \"reason\": \" \"}]}, {\"condition_id\": \"12\", \"met_by\": []}]"
    })
    @DisplayName("보낸 조건을 하나씩 모두 판단하지 않았거나 보내지 않은 카드·겹친 카드·빈 이유가 오면 응답 전체를 받지 않는다")
    void rejectsResponseThatBreaksTheContract(String judgements) {

        responseBody = success("{\"judgements\": " + judgements + "}");

        assertThatThrownBy(() -> adapter.judge(request()))
                .isInstanceOfSatisfying(AgentUnavailableException.class,
                        failure -> assertThat(failure.isRetryable()).isFalse());

    }

    @Test
    @DisplayName("AI 가 503 을 주면 다시 시도해도 되는 실패로 던진다")
    void turnsAiFailureIntoRetryableFailure() {

        responseStatus = 503;
        responseBody = """
                {"success": false, "data": null, "meta": null,
                 "error": {"code": "LLM_UNAVAILABLE", "message": "조건 판단 LLM 답을 받지 못했습니다.", "retryable": true}}""";

        assertThatThrownBy(() -> adapter.judge(request()))
                .isInstanceOfSatisfying(AgentUnavailableException.class,
                        failure -> assertThat(failure.isRetryable()).isTrue());

    }

    private static MatchJudgementRequest request() {

        return new MatchJudgementRequest("에이크미", "백엔드 개발자",
                List.of(new JudgeCondition("11", PostingCriterionType.REQUIRED, "Spring 으로 API 를 개발한 경험"),
                        new JudgeCondition("12", PostingCriterionType.PREFERRED, "Redis 경험")),
                List.of(new JudgeCard("1", "결제 API", null, null, "Spring 으로 결제 승인 API 를 만들었다", null,
                                List.of("JAVA", "SPRING")),
                        new JudgeCard("2", "캐시 도입", null, null, null, "조회를 300ms 에서 40ms 로 줄였다",
                                List.of("REDIS"))));

    }

    private static String success(String data) {

        return "{\"success\": true, \"data\": " + data
                + ", \"meta\": {\"model\": null, \"tool_calls_made\": 0, \"processing_ms\": 3}, \"error\": null}";

    }
}
