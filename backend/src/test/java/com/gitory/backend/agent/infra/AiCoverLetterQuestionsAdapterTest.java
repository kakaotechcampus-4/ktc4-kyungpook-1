package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterQuestions;
import com.gitory.backend.agent.port.CoverLetterQuestionsRequest;
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
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCoverLetterQuestionsAdapterTest {

    private static final CoverLetterQuestionsRequest REQUEST = new CoverLetterQuestionsRequest(7L, "백엔드 개발", null,
            List.of(new CoverLetterQuestionsRequest.Question(1, "협업 경험을 쓰세요", 700, "COLLABORATION",
                            new CoverLetterQuestionsRequest.CardDetail(11L, "배포 시간 단축", "TECH", "grow22/gitory",
                                    List.of(new CoverLetterQuestionsRequest.Statement("A", "빌드를 병렬로 돌렸다"))),
                            null,
                            List.of(new CoverLetterQuestionsRequest.KnownAnswer("LEARNING", "작게 나눠 배포하게 됐다", 11L))),
                    new CoverLetterQuestionsRequest.Question(2, "실패 경험을 쓰세요", 500, null, null, null, List.of())));

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void setUp() throws Exception {

        responseBody = success(validData());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
    @DisplayName("질문 만들기 API 에 카드 전문과 지난 답을 snake_case 로 보내고, 문항마다의 질문과 꼭 필요한 항목을 돌려받는다")
    void postsSnakeCaseRequestAndReadsAsks() {

        CoverLetterQuestions result = adapter().ask(REQUEST);

        JsonNode sent = mapper.readTree(requestBody);
        JsonNode first = sent.path("questions").get(0);
        assertThat(requestPath).isEqualTo("/internal/cover-letter-questions");
        assertThat(sent.path("cover_letter_id").asLong()).isEqualTo(7L);
        assertThat(first.path("question_theme").asString()).isEqualTo("COLLABORATION");
        assertThat(first.path("primary_card").path("statements").get(0).path("star_slot").asString()).isEqualTo("A");
        assertThat(first.path("known_answers").get(0).path("card_id").asLong()).isEqualTo(11L);
        assertThat(sent.path("questions").get(1).path("primary_card").isNull()).isTrue();
        assertThat(result.questions()).containsExactly(
                new CoverLetterQuestions.Question(1, List.of("RESULT"), List.of(
                        new CoverLetterQuestions.Ask("RESULT", "배포가 얼마나 빨라졌나요?"),
                        new CoverLetterQuestions.Ask("TRIGGER", "왜 고치기로 했나요?"))),
                new CoverLetterQuestions.Question(2, List.of(), List.of()));

    }

    @ParameterizedTest
    @ValueSource(strings = {"missingQuestion", "swappedQuestions", "unknownItem", "unknownRequiredItem",
            "sameItemTwice", "fourAsks", "blankQuestion"})
    @DisplayName("문항이 빠지거나 순서가 다르거나, 모르는 항목·같은 항목을 묻거나, 3개를 넘게 묻거나, 빈 질문이 온 응답은 다시 해도 같은 이상 응답이다")
    void rejectsResponsesOutsideTheContract(String broken) {

        ObjectNode data = validData();
        ObjectNode first = (ObjectNode) data.withArray("questions").get(0);
        switch (broken) {
            case "missingQuestion" -> data.withArray("questions").remove(1);
            case "swappedQuestions" -> data.withArray("questions").add(data.withArray("questions").remove(0));
            case "unknownItem" -> ((ObjectNode) first.withArray("asks").get(0)).put("item", "HOBBY");
            case "unknownRequiredItem" -> first.withArray("required_missing").add("HOBBY");
            case "sameItemTwice" -> ((ObjectNode) first.withArray("asks").get(1)).put("item", "RESULT");
            case "fourAsks" -> {
                first.withArray("asks").addObject().put("item", "LEARNING").put("question", "무엇을 배웠나요?");
                first.withArray("asks").addObject().put("item", "JOB_LINK").put("question", "직무와 어떻게 이어지나요?");
            }
            case "blankQuestion" -> ((ObjectNode) first.withArray("asks").get(0)).put("question", " ");
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().ask(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isFalse();
                    assertThat(failure.isInvalidResponse()).isTrue();
                });

    }

    @Test
    @DisplayName("AI 가 잠시 못 한다고 알리면 다시 시도할 수 있는 실패로 넘긴다")
    void passesTemporaryFailureAsRetryable() {

        responseStatus = 503;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":{},"
                + "\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}";

        assertThatThrownBy(() -> adapter().ask(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class,
                        failure -> assertThat(failure.isRetryable()).isTrue());

    }

    private ObjectNode validData() {

        ObjectNode data = mapper.createObjectNode().put("cover_letter_id", 7);
        ObjectNode first = data.withArray("questions").addObject().put("question_id", 1).put("source", "LLM");
        first.withArray("required_missing").add("RESULT");
        first.withArray("asks").addObject().put("item", "RESULT").put("question", "배포가 얼마나 빨라졌나요?");
        first.withArray("asks").addObject().put("item", "TRIGGER").put("question", "왜 고치기로 했나요?");
        ObjectNode second = data.withArray("questions").addObject().put("question_id", 2).put("source", "NONE");
        second.putArray("required_missing");
        second.putArray("asks");
        return data;

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private AiCoverLetterQuestionsAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiCoverLetterQuestionsAdapter(new AiHttpClient(properties, mapper), properties);

    }
}
