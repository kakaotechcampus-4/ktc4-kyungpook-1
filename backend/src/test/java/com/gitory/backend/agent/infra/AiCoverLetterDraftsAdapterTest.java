package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterDraftsRequest;
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

class AiCoverLetterDraftsAdapterTest {

    private static final CoverLetterDraftsRequest REQUEST = new CoverLetterDraftsRequest(7L, "백엔드 개발", null,
            List.of(new CoverLetterDraftsRequest.Question(1, "협업 경험을 쓰세요", 700, "COLLABORATION",
                            new CoverLetterQuestionsRequest.CardDetail(11L, "배포 시간 단축", "TECH", null,
                                    List.of(new CoverLetterQuestionsRequest.Statement("A", "빌드를 병렬로 돌렸다"))),
                            null,
                            List.of(new CoverLetterQuestionsRequest.KnownAnswer("RESULT", "배포가 3분 빨라졌다", 11L),
                                    new CoverLetterQuestionsRequest.KnownAnswer("REASON", "결제를 다뤄 보고 싶다", null)),
                            List.of("LEARNING")),
                    new CoverLetterDraftsRequest.Question(2, "실패 경험을 쓰세요", 500, null, null, null, List.of(),
                            List.of())));

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
    @DisplayName("초안 쓰기 API 에 답과 받지 못한 항목을 snake_case 로 보내고, 문항마다의 초안과 쓰기 과정을 돌려받는다")
    void postsSnakeCaseRequestAndReadsDrafts() {

        CoverLetterDrafts result = adapter().write(REQUEST);

        JsonNode first = mapper.readTree(requestBody).path("questions").get(0);
        assertThat(requestPath).isEqualTo("/internal/cover-letter-drafts");
        assertThat(first.path("missing_items").get(0).asString()).isEqualTo("LEARNING");
        assertThat(first.path("answers").get(0).path("card_id").asLong()).isEqualTo(11L);
        assertThat(first.path("answers").get(1).path("card_id").isNull()).isTrue();
        assertThat(result.drafts()).containsExactly(
                new CoverLetterDrafts.Draft(1, "배포를 함께 고쳤다. 배포가 30% 빨라졌다.",
                        List.of(new CoverLetterDrafts.Sentence(1, "배포를 함께 고쳤다."),
                                new CoverLetterDrafts.Sentence(2, "배포가 30% 빨라졌다.")),
                        24, 0, List.of(new CoverLetterDrafts.Mark(2, "UNSOURCED_NUMBER", "30%")), List.of("LEARNING"),
                        "LLM"),
                new CoverLetterDrafts.Draft(2, "[상황: 언제, 어디서, 무슨 일이었는지 적어 주세요]",
                        List.of(new CoverLetterDrafts.Sentence(1, "[상황: 언제, 어디서, 무슨 일이었는지 적어 주세요]")),
                        0, 0, List.of(), List.of("SITUATION"), "SKELETON"));
        assertThat(result.steps()).containsExactly(
                new CoverLetterDrafts.Step(1, "SUB_AGENT", "WROTE", 1, null, "24", null, null, null),
                new CoverLetterDrafts.Step(2, "SUB_AGENT", "DECIDED", 1, "UNSOURCED_NUMBER", "30%", null, "KEEP",
                        "카드의 숫자와 같다"),
                new CoverLetterDrafts.Step(3, "SUPERVISOR", "REVIEWED", null, null, "0", null, null, null));

    }

    @ParameterizedTest
    @ValueSource(strings = {"missingQuestion", "swappedQuestions", "markOnMissingSentence", "markCodeOnlyForSteps",
            "unknownBlank", "unknownSource", "negativeCharCount", "blankText", "unsentStepQuestion", "unknownStepKind",
            "unknownAction"})
    @DisplayName("문항이 빠지거나 순서가 다르거나, 없는 문장에 표시를 달거나, 모르는 값·음수 글자 수·빈 초안이 온 응답은 다시 해도 같은 이상 응답이다")
    void rejectsResponsesOutsideTheContract(String broken) {

        ObjectNode data = validData();
        ObjectNode first = (ObjectNode) data.withArray("drafts").get(0);
        ObjectNode firstStep = (ObjectNode) data.withArray("steps").get(0);
        switch (broken) {
            case "missingQuestion" -> data.withArray("drafts").remove(1);
            case "swappedQuestions" -> data.withArray("drafts").add(data.withArray("drafts").remove(0));
            case "markOnMissingSentence" -> ((ObjectNode) first.withArray("marks").get(0)).put("sentence_seq", 3);
            case "markCodeOnlyForSteps" -> ((ObjectNode) first.withArray("marks").get(0)).put("code", "OVER_LIMIT");
            case "unknownBlank" -> first.withArray("blanks").add("HOBBY");
            case "unknownSource" -> first.put("source", "HUMAN");
            case "negativeCharCount" -> first.put("char_count", -1);
            case "blankText" -> first.put("text", " ");
            case "unsentStepQuestion" -> firstStep.put("question_id", 3);
            case "unknownStepKind" -> firstStep.put("kind", "GUESSED");
            case "unknownAction" -> ((ObjectNode) data.withArray("steps").get(1)).put("action", "IGNORE");
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().write(REQUEST))
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

        assertThatThrownBy(() -> adapter().write(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class,
                        failure -> assertThat(failure.isRetryable()).isTrue());

    }

    private ObjectNode validData() {

        ObjectNode data = mapper.createObjectNode().put("cover_letter_id", 7);
        ObjectNode first = data.withArray("drafts").addObject().put("question_id", 1)
                .put("text", "배포를 함께 고쳤다. 배포가 30% 빨라졌다.").put("char_count", 24).put("over_by", 0)
                .put("rewrites", 1).put("source", "LLM");
        first.withArray("sentences").addObject().put("seq", 1).put("text", "배포를 함께 고쳤다.");
        first.withArray("sentences").addObject().put("seq", 2).put("text", "배포가 30% 빨라졌다.");
        first.withArray("marks").addObject().put("sentence_seq", 2).put("code", "UNSOURCED_NUMBER").put("value", "30%");
        first.withArray("blanks").add("LEARNING");
        ObjectNode second = data.withArray("drafts").addObject().put("question_id", 2)
                .put("text", "[상황: 언제, 어디서, 무슨 일이었는지 적어 주세요]").put("char_count", 0).put("over_by", 0)
                .put("rewrites", 0).put("source", "SKELETON");
        second.withArray("sentences").addObject().put("seq", 1).put("text", "[상황: 언제, 어디서, 무슨 일이었는지 적어 주세요]");
        second.putArray("marks");
        second.withArray("blanks").add("SITUATION");
        data.withArray("steps").addObject().put("seq", 1).put("actor", "SUB_AGENT").put("kind", "WROTE")
                .put("question_id", 1).putNull("code").put("value", "24");
        data.withArray("steps").addObject().put("seq", 2).put("actor", "SUB_AGENT").put("kind", "DECIDED")
                .put("question_id", 1).put("code", "UNSOURCED_NUMBER").put("value", "30%").put("action", "KEEP")
                .put("reason", "카드의 숫자와 같다");
        data.withArray("steps").addObject().put("seq", 3).put("actor", "SUPERVISOR").put("kind", "REVIEWED")
                .putNull("question_id").put("value", "0");
        return data;

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private AiCoverLetterDraftsAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiCoverLetterDraftsAdapter(new AiHttpClient(properties, mapper), properties);

    }
}
