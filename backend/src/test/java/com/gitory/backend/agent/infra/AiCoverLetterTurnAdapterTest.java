package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterFill;
import com.gitory.backend.agent.port.CoverLetterFillRequest;
import com.gitory.backend.agent.port.CoverLetterPolishRequest;
import com.gitory.backend.agent.port.CoverLetterQuestionsRequest;
import com.gitory.backend.agent.port.CoverLetterTurnReply;
import com.gitory.backend.agent.port.CoverLetterTurnRequest;
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

class AiCoverLetterTurnAdapterTest {

    private static final String BLANK = "[행동: 직접 한 일을 적어 주세요]";
    private static final CoverLetterQuestionsRequest.CardDetail CARD = new CoverLetterQuestionsRequest.CardDetail(11L,
            "배포 시간 단축", "TECH", null, List.of(new CoverLetterQuestionsRequest.Statement("A", "빌드를 병렬로 돌렸다")));
    private static final List<CoverLetterPolishRequest.Other> OTHERS = List.of(
            new CoverLetterPolishRequest.Other(2, "지원 동기를 쓰세요", "결제를 다뤄 보고 싶다."));
    private static final CoverLetterTurnRequest REPLY = turnRequest("USER_REPLY");
    private static final CoverLetterFillRequest FILL = new CoverLetterFillRequest(7L, "백엔드 개발", null,
            List.of("METICULOUS"), null, OTHERS,
            new CoverLetterPolishRequest.Target(1, "협업 경험을 쓰세요", 700, "COLLABORATION", CARD, null,
                    List.of(new CoverLetterQuestionsRequest.KnownAnswer("ACTION", "로그를 같이 보며 원인을 좁혔다", 11L)),
                    "배포를 함께 고쳤다. " + BLANK, List.of()),
            "ACTION", BLANK);

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void setUp() throws Exception {

        responseBody = success(validReply());
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
    @DisplayName("대화 API 에 문항 재료·지금 글·빈칸과 대화 전체·계기를 snake_case 로 보내고, 다음 말·정리한 답·상태를 돌려받는다")
    void postsSnakeCaseTurnRequestAndReadsReply() {

        CoverLetterTurnReply reply = adapter().next(REPLY);

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/cover-letter-turns");
        assertThat(sent.path("strengths").get(0).asString()).isEqualTo("METICULOUS");
        assertThat(sent.path("cover_letter_id").asLong()).isEqualTo(7L);
        assertThat(sent.has("posting")).isFalse();
        assertThat(sent.path("trigger").asString()).isEqualTo("USER_REPLY");
        assertThat(sent.path("question").path("question_id").asInt()).isEqualTo(1);
        assertThat(sent.path("question").path("question_theme").asString()).isEqualTo("COLLABORATION");
        assertThat(sent.path("question").path("primary_card").path("card_id").asLong()).isEqualTo(11L);
        assertThat(sent.path("question").path("current_text").asString()).isEqualTo("배포를 함께 고쳤다. " + BLANK);
        assertThat(sent.path("question").path("blanks").get(0).asString()).isEqualTo(BLANK);
        assertThat(sent.path("question").path("missing_evidence").isArray()).isTrue();
        assertThat(sent.path("others").get(0).path("current_text").asString()).isEqualTo("결제를 다뤄 보고 싶다.");
        assertThat(sent.path("thread").get(0).path("role").asString()).isEqualTo("AI");
        assertThat(sent.path("thread").get(0).path("item").asString()).isEqualTo("ACTION");
        assertThat(sent.path("thread").get(0).path("status").asString()).isEqualTo("ASKING");
        assertThat(sent.path("thread").get(1).path("status").isNull()).isTrue();
        assertThat(sent.path("thread").get(1).path("role").asString()).isEqualTo("USER");
        assertThat(sent.path("thread").get(1).path("question_id").asInt()).isEqualTo(1);
        assertThat(reply).isEqualTo(new CoverLetterTurnReply(
                new CoverLetterTurnReply.Message("로그를 같이 봤군요. 이 답으로 채울게요.", 1, "ACTION"),
                List.of(new CoverLetterTurnReply.Answer("ACTION", "로그를 같이 보며 원인을 좁혔다", true)), "FILLED"));

    }

    @Test
    @DisplayName("채우기 API 에 대상 문항과 채운 항목·빈칸을 snake_case 로 보내고, 다시 쓴 글을 돌려받는다")
    void postsSnakeCaseFillRequestAndReadsDraft() {

        responseBody = success(validFill());

        CoverLetterFill filled = adapter().fill(FILL);

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/cover-letter-fill");
        assertThat(sent.path("strengths").get(0).asString()).isEqualTo("METICULOUS");
        assertThat(sent.path("filled_item").asString()).isEqualTo("ACTION");
        assertThat(sent.path("blank").asString()).isEqualTo(BLANK);
        assertThat(sent.path("target").path("current_text").asString()).isEqualTo("배포를 함께 고쳤다. " + BLANK);
        assertThat(sent.path("target").path("answers").get(0).path("text").asString()).isEqualTo("로그를 같이 보며 원인을 좁혔다");
        assertThat(sent.path("others").get(0).path("question_id").asInt()).isEqualTo(2);
        assertThat(filled.reverted()).isFalse();
        assertThat(filled.draft()).isEqualTo(new CoverLetterDrafts.Draft(1, "배포를 함께 고쳤다. 로그를 같이 보며 원인을 좁혔다.",
                List.of(new CoverLetterDrafts.Sentence(1, "배포를 함께 고쳤다."),
                        new CoverLetterDrafts.Sentence(2, "로그를 같이 보며 원인을 좁혔다.")),
                26, 0, List.of(new CoverLetterDrafts.Mark(2, "INFERRED", null)), List.of(), "LLM"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownStatus", "unknownMessageItem", "blankMessageText", "unsentQuestion",
            "missingMessage", "filledWithoutAnswer", "unknownAnswerItem", "blankAnswerText", "tooLongAnswer",
            "sufficientNotBoolean", "answerOnOpen", "filledWithEmptyAnswers", "missingAnswers", "duplicateAnswerItem"})
    @DisplayName("모르는 상태·항목, 빈 말, 보내지 않은 문항을 가리킨 말, 정리한 답 없이 채웠다는 응답, 1,000자를 넘는 답, 사용자가 답하지 않았는데 온 답은 다시 해도 같은 이상 응답이다")
    void rejectsTurnRepliesOutsideTheContract(String broken) {

        ObjectNode data = validReply();
        ObjectNode message = (ObjectNode) data.path("message");
        ObjectNode answer = (ObjectNode) data.path("answers").get(0);
        CoverLetterTurnRequest request = REPLY;
        switch (broken) {
            case "unknownStatus" -> data.put("status", "WAITING");
            case "unknownMessageItem" -> message.put("item", "SKILL");
            case "blankMessageText" -> message.put("text", " ");
            case "unsentQuestion" -> message.put("question_id", 3);
            case "missingMessage" -> data.remove("message");
            case "filledWithoutAnswer", "filledWithEmptyAnswers" -> data.putArray("answers");
            case "missingAnswers" -> data.remove("answers");
            case "duplicateAnswerItem" -> data.withArray("answers").addObject().put("item", "ACTION")
                    .put("text", "다른 답이다").put("sufficient", true);
            case "unknownAnswerItem" -> answer.put("item", "SKILL");
            case "blankAnswerText" -> answer.put("text", "");
            case "tooLongAnswer" -> answer.put("text", "가".repeat(1_001));
            case "sufficientNotBoolean" -> answer.put("sufficient", "yes");
            case "answerOnOpen" -> {
                data.put("status", "ASKING");
                request = turnRequest("OPEN");
            }
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);
        CoverLetterTurnRequest sent = request;

        assertThatThrownBy(() -> adapter().next(sent))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isFalse();
                    assertThat(failure.isInvalidResponse()).isTrue();
                });

    }

    @Test
    @DisplayName("정리한 답 목록은 없어도, 하나여도, 여럿이어도 순서대로 읽고, 항목이 없는 말(열린 질문)도 읽는다")
    void readsZeroOneAndManyAnswersAndAMessageWithoutItem() {

        ObjectNode none = validReply();
        none.put("status", "ASKING").putArray("answers");
        ((ObjectNode) none.path("message")).putNull("item");
        responseBody = success(none);
        CoverLetterTurnReply asking = adapter().next(REPLY);
        assertThat(asking.answers()).isEmpty();
        assertThat(asking.message().item()).isNull();

        ObjectNode many = validReply();
        many.withArray("answers").addObject().put("item", "RESULT").put("text", "배포가 빨라졌다").put("sufficient", false);
        responseBody = success(many);
        assertThat(adapter().next(REPLY).answers()).containsExactly(
                new CoverLetterTurnReply.Answer("ACTION", "로그를 같이 보며 원인을 좁혔다", true),
                new CoverLetterTurnReply.Answer("RESULT", "배포가 빨라졌다", false));

    }

    @Test
    @DisplayName("다시 쓰기 과정에 REVERTED 가 있으면 AI 가 앞 글로 되돌린 것으로 알린다")
    void marksRevertedFill() {

        ObjectNode data = validFill();
        data.withArray("steps").addObject().put("seq", 2).put("actor", "CODE").put("kind", "REVERTED")
                .put("question_id", 1);
        responseBody = success(data);

        assertThat(adapter().fill(FILL).reverted()).isTrue();

    }

    @ParameterizedTest
    @ValueSource(strings = {"otherQuestionDraft", "unknownMarkCode", "markOnMissingSentence", "unknownBlank",
            "unknownSource", "unknownStepKind", "otherQuestionStep", "missingSteps", "missingDraft"})
    @DisplayName("다른 문항의 글, 초안 쓰기 허용 목록 밖의 표시·빈칸·출처·과정, 과정이 빠진 응답은 다시 해도 같은 이상 응답이다")
    void rejectsFillResponsesOutsideTheContract(String broken) {

        ObjectNode data = validFill();
        ObjectNode draft = (ObjectNode) data.path("draft");
        switch (broken) {
            case "otherQuestionDraft" -> draft.put("question_id", 2);
            case "unknownMarkCode" -> ((ObjectNode) draft.withArray("marks").get(0)).put("code", "FILLED");
            case "markOnMissingSentence" -> ((ObjectNode) draft.withArray("marks").get(0)).put("sentence_seq", 3);
            case "unknownBlank" -> draft.withArray("blanks").add("SKILL");
            case "unknownSource" -> draft.put("source", "TEMPLATE");
            case "unknownStepKind" -> ((ObjectNode) data.withArray("steps").get(0)).put("kind", "FILLED");
            case "otherQuestionStep" -> ((ObjectNode) data.withArray("steps").get(0)).put("question_id", 2);
            case "missingSteps" -> data.remove("steps");
            case "missingDraft" -> data.remove("draft");
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().fill(FILL))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isFalse();
                    assertThat(failure.isInvalidResponse()).isTrue();
                });

    }

    @Test
    @DisplayName("AI 가 LLM 을 부르지 못해 503 을 돌려주면 다음 말과 다시 쓰기 모두 다시 시도할 수 있는 실패로 넘긴다")
    void passesLlmUnavailableAsRetryable() {

        responseStatus = 503;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":{},"
                + "\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}";

        assertThatThrownBy(() -> adapter().next(REPLY))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.isInvalidResponse()).isFalse();
                });
        assertThatThrownBy(() -> adapter().fill(FILL))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.isInvalidResponse()).isFalse();
                });

    }

    private static CoverLetterTurnRequest turnRequest(String trigger) {

        return new CoverLetterTurnRequest(7L, "백엔드 개발", null, List.of("METICULOUS"), null,
                new CoverLetterTurnRequest.Question(1, "협업 경험을 쓰세요", 700, "COLLABORATION", CARD, null, List.of(),
                        "배포를 함께 고쳤다. " + BLANK, List.of(BLANK), List.of()),
                OTHERS,
                List.of(new CoverLetterTurnRequest.Turn(1, "AI", 1, "그때 직접 한 일을 알려 주세요.", "ACTION", "ASKING"),
                        new CoverLetterTurnRequest.Turn(2, "USER", 1, "AI 담당 팀원과 로그를 같이 봤어요", null, null)),
                trigger);

    }

    private ObjectNode validReply() {

        ObjectNode data = mapper.createObjectNode().put("status", "FILLED");
        data.putObject("message").put("text", "로그를 같이 봤군요. 이 답으로 채울게요.").put("question_id", 1)
                .put("item", "ACTION");
        data.putArray("answers").addObject().put("item", "ACTION").put("text", "로그를 같이 보며 원인을 좁혔다").put("sufficient", true);
        return data;

    }

    private ObjectNode validFill() {

        ObjectNode data = mapper.createObjectNode();
        ObjectNode draft = data.putObject("draft").put("question_id", 1).put("text", "배포를 함께 고쳤다. 로그를 같이 보며 원인을 좁혔다.")
                .put("char_count", 26).put("over_by", 0).put("rewrites", 0).put("source", "LLM");
        draft.withArray("sentences").addObject().put("seq", 1).put("text", "배포를 함께 고쳤다.");
        draft.withArray("sentences").addObject().put("seq", 2).put("text", "로그를 같이 보며 원인을 좁혔다.");
        draft.withArray("marks").addObject().put("sentence_seq", 2).put("code", "INFERRED").putNull("value");
        draft.putArray("blanks");
        data.withArray("steps").addObject().put("seq", 1).put("actor", "SUB_AGENT").put("kind", "REWROTE")
                .put("question_id", 1).put("value", "26");
        return data;

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private AiCoverLetterTurnAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiCoverLetterTurnAdapter(new AiHttpClient(properties, mapper));

    }
}
