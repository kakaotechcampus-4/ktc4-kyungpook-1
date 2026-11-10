package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterSuggestionRequest;
import com.gitory.backend.agent.port.CoverLetterSuggestions;
import com.gitory.backend.common.infra.ai.AiClientProperties;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCoverLetterSuggestionAdapterTest {

    private static final String CANDIDATE_ID = "6f1c1b0e-0000-4000-8000-000000000001";
    private static final CoverLetterSuggestionRequest REQUEST = new CoverLetterSuggestionRequest(7L, "백엔드 개발", null,
            new CoverLetterSuggestionRequest.Question(1, "협업 경험을 쓰세요", 700, "COLLABORATION"),
            List.of(new CoverLetterSuggestionRequest.Card(12L, "배포 시간 단축", "TECH", "owner/repo", null,
                    "빌드를 병렬로 돌렸다")),
            List.of(new CoverLetterSuggestionRequest.Candidate(CANDIDATE_ID, "주문 큐 만들기", "리뷰가 많았다",
                    "owner/repo", List.of("feat: 큐"), 9)),
            null);

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestBody;
    private volatile long responseDelayMs;

    @BeforeEach
    void setUp() throws Exception {

        responseBody = success(validSuggestions());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
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
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

    }

    @AfterEach
    void tearDown() {

        server.stop(0);

    }

    @Test
    @DisplayName("제안 API 에 문항·카드·후보를 snake_case 로 보내고, 보낸 카드·후보를 가리킨 제안을 순서대로 돌려받는다")
    void postsSnakeCaseRequestAndReadsSuggestions() {

        CoverLetterSuggestions result = adapter().suggest(REQUEST);

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/cover-letter-suggestions");
        assertThat(sent.path("cover_letter_id").asLong()).isEqualTo(7L);
        assertThat(sent.has("posting")).isFalse();
        assertThat(sent.path("question").path("question_theme").asString()).isEqualTo("COLLABORATION");
        assertThat(sent.path("cards").get(0).path("card_id").asLong()).isEqualTo(12L);
        assertThat(sent.path("cards").get(0).path("card_kind").asString()).isEqualTo("TECH");
        assertThat(sent.path("candidates").get(0).path("candidate_id").asString()).isEqualTo(CANDIDATE_ID);
        assertThat(sent.path("candidates").get(0).path("commit_messages").get(0).asString()).isEqualTo("feat: 큐");
        assertThat(sent.path("candidates").get(0).path("review_comment_count").asInt()).isEqualTo(9);
        assertThat(result.suggestions()).containsExactly(
                new CoverLetterSuggestions.Item("CARD", "12", "배포를 함께 고친 과정을 협업으로 쓸 수 있어요"),
                new CoverLetterSuggestions.Item("CANDIDATE", CANDIDATE_ID, "리뷰어와 의견을 맞춘 과정을 쓸 수 있어요"));

    }

    @Test
    @DisplayName("맞는 것이 없어 빈 목록이 와도 정상 응답이다")
    void acceptsAnEmptyList() {

        ObjectNode data = mapper.createObjectNode();
        data.putArray("suggestions");
        responseBody = success(data);

        assertThat(adapter().suggest(REQUEST).suggestions()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownCardId", "unknownCandidateId", "cardIdAsCandidate", "candidateIdAsCard",
            "unknownSource", "duplicate", "fourItems", "blankAngle", "longAngle", "missingList"})
    @DisplayName("보내지 않은 카드·후보, id 와 종류가 맞지 않는 제안, 같은 것을 두 번 고른 응답, 4개 넘는 제안, 빈 문구·60자를 넘는 문구, 목록이 없는 응답은 다시 해도 같은 이상 응답이다")
    void rejectsResponsesOutsideTheContract(String broken) {

        ObjectNode data = validSuggestions();
        ArrayNode list = data.withArray("suggestions");
        switch (broken) {
            case "unknownCardId" -> ((ObjectNode) list.get(0)).put("id", "99");
            case "unknownCandidateId" -> ((ObjectNode) list.get(1)).put("id", "6f1c1b0e-0000-4000-8000-000000000009");
            case "cardIdAsCandidate" -> ((ObjectNode) list.get(0)).put("source", "CANDIDATE");
            case "candidateIdAsCard" -> ((ObjectNode) list.get(1)).put("source", "CARD");
            case "unknownSource" -> ((ObjectNode) list.get(0)).put("source", "REPO");
            case "duplicate" -> list.addObject().put("source", "CARD").put("id", "12").put("angle", "또 같은 카드예요");
            case "fourItems" -> {
                list.addObject().put("source", "CARD").put("id", "12").put("angle", "하나");
                list.addObject().put("source", "CARD").put("id", "12").put("angle", "둘");
            }
            case "blankAngle" -> ((ObjectNode) list.get(0)).put("angle", "  ");
            case "longAngle" -> ((ObjectNode) list.get(0)).put("angle", "가".repeat(61));
            case "missingList" -> data.remove("suggestions");
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().suggest(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isFalse();
                    assertThat(failure.isInvalidResponse()).isTrue();
                });

    }

    @Test
    @DisplayName("응답이 제안 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다 — 공통 대기 시간이 길어도 제안 값만 따른다")
    void timesOutBySuggestReadTimeoutOnly() {

        responseDelayMs = 600;
        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .suggestReadTimeout(Duration.ofMillis(100)).build();
        AiCoverLetterSuggestionAdapter adapter = new AiCoverLetterSuggestionAdapter(new AiHttpClient(properties, mapper), properties);

        assertThatThrownBy(() -> adapter.suggest(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.isInvalidResponse()).isFalse();
                });

    }

    @Test
    @DisplayName("AI 가 LLM 을 부르지 못해 503 을 돌려주면 다시 시도할 수 있는 실패로 넘긴다")
    void passesLlmUnavailableAsRetryable() {

        responseStatus = 503;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":{},"
                + "\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}";

        assertThatThrownBy(() -> adapter().suggest(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.isInvalidResponse()).isFalse();
                });

    }

    private ObjectNode validSuggestions() {

        ObjectNode data = mapper.createObjectNode();
        data.withArray("suggestions").addObject().put("source", "CARD").put("id", "12")
                .put("angle", "배포를 함께 고친 과정을 협업으로 쓸 수 있어요");
        data.withArray("suggestions").addObject().put("source", "CANDIDATE").put("id", CANDIDATE_ID)
                .put("angle", "리뷰어와 의견을 맞춘 과정을 쓸 수 있어요");
        return data;

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private AiCoverLetterSuggestionAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiCoverLetterSuggestionAdapter(new AiHttpClient(properties, mapper), properties);

    }
}
