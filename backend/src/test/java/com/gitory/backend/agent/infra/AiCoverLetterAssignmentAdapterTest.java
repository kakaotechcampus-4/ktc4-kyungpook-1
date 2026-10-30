package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterAssignment;
import com.gitory.backend.agent.port.CoverLetterAssignmentRequest;
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

class AiCoverLetterAssignmentAdapterTest {

    private static final CoverLetterAssignmentRequest REQUEST = new CoverLetterAssignmentRequest(7L, "백엔드 개발",
            null,
            List.of(new CoverLetterAssignmentRequest.Question(1, "협업 경험을 쓰세요", 700),
                    new CoverLetterAssignmentRequest.Question(2, "실패 경험을 쓰세요", 500)),
            List.of(new CoverLetterAssignmentRequest.Card(11L, "배포 시간 단축", "TECH", "grow22/gitory", "2025.09",
                            "빌드를 병렬로 돌렸다"),
                    new CoverLetterAssignmentRequest.Card(12L, "동아리 회계", "QUALITATIVE", null, null, "장부를 정리했다")));

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
    @DisplayName("카드 고르기 API 에 snake_case 로 보내고, 문항마다의 배정과 과정을 돌려받는다")
    void postsSnakeCaseRequestAndReadsAssignments() {

        CoverLetterAssignment result = adapter().assign(REQUEST);

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/cover-letter-assignments");
        assertThat(sent.path("cover_letter_id").asLong()).isEqualTo(7L);
        assertThat(sent.path("questions").get(1).path("char_limit").asInt()).isEqualTo(500);
        assertThat(sent.path("cards").get(0).path("card_kind").asString()).isEqualTo("TECH");
        assertThat(sent.path("cards").get(0).path("source_name").asString()).isEqualTo("grow22/gitory");
        assertThat(result.assignments()).containsExactly(
                new CoverLetterAssignment.Question(1, "COLLABORATION", 11L, 12L, "배포를 함께 고친 경험",
                        List.of(new CoverLetterAssignment.Considered(12L, "협업이 덜 드러남")), "SUPERVISOR"),
                new CoverLetterAssignment.Question(2, null, null, null, null, List.of(), "FAILED"));
        assertThat(result.steps()).containsExactly(
                new CoverLetterAssignment.Step(1, "SUB_AGENT", "PICKED", 1, 11L, "배포를 함께 고친 경험"),
                new CoverLetterAssignment.Step(2, "SUPERVISOR", "CHECKED", null, null, null));

    }

    @ParameterizedTest
    @ValueSource(strings = {"unsentCard", "unsentConsideredCard", "missingQuestion", "swappedQuestions",
            "unknownDecider", "unknownTheme", "unsentStepQuestion", "unknownStepKind"})
    @DisplayName("보내지 않은 카드·문항을 가리키거나 문항이 빠지거나 순서가 다르거나 모르는 값이 온 응답은 다시 해도 같은 이상 응답이다")
    void rejectsResponsesOutsideTheRequest(String broken) {

        ObjectNode data = validData();
        ObjectNode first = (ObjectNode) data.withArray("assignments").get(0);
        switch (broken) {
            case "unsentCard" -> first.put("primary_card_id", 99);
            case "unsentConsideredCard" -> ((ObjectNode) first.withArray("considered").get(0)).put("card_id", 99);
            case "missingQuestion" -> data.withArray("assignments").remove(1);
            case "swappedQuestions" -> {
                data.withArray("assignments").add(data.withArray("assignments").remove(0));
            }
            case "unknownDecider" -> first.put("decided_by", "USER");
            case "unknownTheme" -> first.put("question_theme", "HOBBY");
            case "unsentStepQuestion" -> ((ObjectNode) data.withArray("steps").get(0)).put("question_id", 3);
            case "unknownStepKind" -> ((ObjectNode) data.withArray("steps").get(0)).put("kind", "GUESSED");
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().assign(REQUEST))
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

        assertThatThrownBy(() -> adapter().assign(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class,
                        failure -> assertThat(failure.isRetryable()).isTrue());

    }

    private ObjectNode validData() {

        ObjectNode data = mapper.createObjectNode().put("cover_letter_id", 7);
        ObjectNode first = data.withArray("assignments").addObject()
                .put("question_id", 1).put("question_theme", "COLLABORATION").put("primary_card_id", 11)
                .put("supporting_card_id", 12).put("reason", "배포를 함께 고친 경험").put("decided_by", "SUPERVISOR");
        first.withArray("considered").addObject().put("card_id", 12).put("note", "협업이 덜 드러남");
        ObjectNode second = data.withArray("assignments").addObject()
                .put("question_id", 2).putNull("question_theme").putNull("primary_card_id")
                .putNull("supporting_card_id").putNull("reason").put("decided_by", "FAILED");
        second.putArray("considered");
        data.withArray("steps").addObject().put("seq", 1).put("actor", "SUB_AGENT").put("kind", "PICKED")
                .put("question_id", 1).put("card_id", 11).put("reason", "배포를 함께 고친 경험");
        data.withArray("steps").addObject().put("seq", 2).put("actor", "SUPERVISOR").put("kind", "CHECKED")
                .putNull("question_id").putNull("card_id").putNull("reason");
        return data;

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private AiCoverLetterAssignmentAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiCoverLetterAssignmentAdapter(new AiHttpClient(properties, mapper), properties);

    }
}
