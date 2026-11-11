package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.AssistantStepReply;
import com.gitory.backend.agent.port.AssistantStepRequest;
import com.gitory.backend.agent.port.AssistantTool;
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

class AiAssistantStepAdapterTest {

    private static final AssistantStepRequest REQUEST = new AssistantStepRequest(
            List.of(new AssistantStepRequest.Message(1, "토스 백엔드 공고에 맞춰 자소서 써 줘")),
            List.of(new AssistantStepRequest.DoneStep(2, AssistantTool.FIND_POSTINGS, true, null,
                    new AssistantStepRequest.Find("토스", List.of("백엔드")), null, null,
                    List.of(new AssistantStepRequest.Posting("p1", "토스", "백엔드 개발자", 2, null, null)), 1, null,
                    null, null, null, null, null, List.of(), null),
                    new AssistantStepRequest.DoneStep(3, AssistantTool.ASK_CARD_GAP, true, null, null, null, null,
                            null, null, null, null, "c1", null, null,
                            new AssistantStepRequest.QuestionAsked("R", "결과는 어땠나요?"), List.of(), null),
                    new AssistantStepRequest.DoneStep(4, AssistantTool.POLISH_COVER_LETTER, true, null, null, null,
                            null, null, null, null, null, null, "l1", null, null,
                            List.of(new AssistantStepRequest.Polished(1, 3)), 2)),
            new AssistantStepRequest.Context(2, List.of(new AssistantStepRequest.Repo("r1", "gitory", true, false)),
                    List.of("백엔드"),
                    List.of(new AssistantStepRequest.Card("c1", "결제 서버 개선", List.of("상황", "결과"))),
                    List.of(new AssistantStepRequest.Letter("l1", "백엔드 개발자", "토스", 3))),
            4);

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void setUp() throws Exception {

        responseBody = success(callReply());
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
    @DisplayName("요청은 snake_case 로 보내고 응답의 행동을 읽는다")
    void postsSnakeCaseRequestAndReadsActions() {

        AssistantStepReply call = adapter().next(REQUEST);

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/assistant/next-step");
        assertThat(sent.has("stepsLeft")).isFalse();
        assertThat(sent.path("steps_left").asInt()).isEqualTo(4);
        assertThat(sent.path("messages").get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(sent.path("messages").get(0).path("text").asString()).isEqualTo("토스 백엔드 공고에 맞춰 자소서 써 줘");
        JsonNode step = sent.path("steps").get(0);
        assertThat(step.path("tool").asString()).isEqualTo("FIND_POSTINGS");
        assertThat(step.path("ok").booleanValue()).isTrue();
        assertThat(step.path("error").isNull()).isTrue();
        assertThat(step.path("find").path("company").asString()).isEqualTo("토스");
        assertThat(step.path("find").path("keywords").get(0).asString()).isEqualTo("백엔드");
        assertThat(step.path("posting_ref").isNull()).isTrue();
        assertThat(step.path("postings").get(0).path("ref").asString()).isEqualTo("p1");
        assertThat(step.path("postings").get(0).path("days_left").asInt()).isEqualTo(2);
        assertThat(step.path("postings").get(0).path("required_met").isNull()).isTrue();
        assertThat(step.path("total_found").asInt()).isEqualTo(1);
        assertThat(sent.path("context").path("confirmed_cards").asInt()).isEqualTo(2);
        assertThat(sent.path("context").path("repos").get(0).path("analyzed").booleanValue()).isTrue();
        assertThat(sent.path("context").path("repos").get(0).path("analyzing").booleanValue()).isFalse();
        assertThat(sent.path("context").path("interests").get(0).asString()).isEqualTo("백엔드");
        JsonNode asked = sent.path("steps").get(1);
        assertThat(asked.path("card_ref").asString()).isEqualTo("c1");
        assertThat(asked.path("question").path("field").asString()).isEqualTo("R");
        assertThat(asked.path("question").path("text").asString()).isEqualTo("결과는 어땠나요?");
        JsonNode polished = sent.path("steps").get(2);
        assertThat(polished.path("letter_ref").asString()).isEqualTo("l1");
        assertThat(polished.path("polished").get(0).path("points").asInt()).isEqualTo(3);
        assertThat(polished.path("remaining_questions").asInt()).isEqualTo(2);
        assertThat(sent.path("context").path("cards").get(0).path("ref").asString()).isEqualTo("c1");
        assertThat(sent.path("context").path("cards").get(0).path("missing").get(1).asString()).isEqualTo("결과");
        assertThat(sent.path("context").path("letters").get(0).path("job_role").asString()).isEqualTo("백엔드 개발자");
        assertThat(sent.path("context").path("letters").get(0).path("question_count").asInt()).isEqualTo(3);
        assertThat(call).isEqualTo(AssistantStepReply.call(AssistantTool.START_COVER_LETTER, null, "p1", null, null, null, null,
                List.of(new AssistantStepReply.Question("지원 동기를 쓰세요", null),
                        new AssistantStepReply.Question("협업 경험을 쓰세요", 700))));

        responseBody = success(askReply());
        assertThat(adapter().next(REQUEST)).isEqualTo(
                AssistantStepReply.ask("어느 레포를 정리할까요?", "CHOICE", List.of("gitory", "gitory-docs")));

        responseBody = success(finishReply());
        assertThat(adapter().next(REQUEST)).isEqualTo(AssistantStepReply.finish("자소서를 쓰기 시작했어요."));

    }

    @Test
    @DisplayName("새 도구는 card_ref, letter_ref, posting_ref 를 읽는다")
    void readsRefsOfTheNewTools() {

        ObjectNode ask = callReply();
        ask.put("tool", "ASK_CARD_GAP").put("card_ref", "c1").putNull("posting_ref");
        ask.putArray("questions");
        responseBody = success(ask);
        assertThat(adapter().next(REQUEST)).isEqualTo(AssistantStepReply.call(AssistantTool.ASK_CARD_GAP, null, null,
                null, "c1", null, null, List.of()));

        ObjectNode polish = callReply();
        polish.put("tool", "POLISH_COVER_LETTER").put("letter_ref", "l1").putNull("posting_ref");
        polish.putArray("questions");
        responseBody = success(polish);
        assertThat(adapter().next(REQUEST)).isEqualTo(AssistantStepReply.call(AssistantTool.POLISH_COVER_LETTER, null,
                null, null, null, "l1", null, List.of()));

        ObjectNode fill = callReply();
        fill.put("tool", "FILL_POSTING_GAPS");
        fill.putArray("questions");
        responseBody = success(fill);
        assertThat(adapter().next(REQUEST)).isEqualTo(AssistantStepReply.call(AssistantTool.FILL_POSTING_GAPS, null,
                "p1", null, null, null, null, List.of()));

    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownAction", "missingAction", "callWithoutTool", "callWithUnknownTool",
            "askWithoutSay", "askWithBlankSay", "askWithoutKind", "askWithUnknownKind", "questionWithoutText",
            "optionsNotArray", "sayNotText"})
    @DisplayName("도구 이름이 없거나 모르는 행동, 말이 빈 질문처럼 모양이 맞지 않는 응답은 다시 해도 같은 이상 응답이다")
    void rejectsRepliesOutsideTheContract(String broken) {

        ObjectNode data = switch (broken) {
            case "askWithoutSay", "askWithBlankSay", "askWithoutKind", "askWithUnknownKind", "optionsNotArray" -> askReply();
            case "sayNotText" -> finishReply();
            default -> callReply();
        };
        switch (broken) {
            case "unknownAction" -> data.put("action", "STOP");
            case "missingAction" -> data.remove("action");
            case "callWithoutTool" -> data.putNull("tool");
            case "callWithUnknownTool" -> data.put("tool", "SEND_EMAIL");
            case "askWithoutSay" -> data.putNull("say");
            case "askWithBlankSay" -> data.put("say", "  ");
            case "askWithoutKind" -> data.putNull("ask_kind");
            case "askWithUnknownKind" -> data.put("ask_kind", "PICK");
            case "questionWithoutText" -> ((ObjectNode) data.withArray("questions").get(0)).put("text", " ");
            case "optionsNotArray" -> data.put("options", "gitory");
            case "sayNotText" -> data.put("say", 3);
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().next(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isFalse();
                    assertThat(failure.isInvalidResponse()).isTrue();
                });

    }

    @Test
    @DisplayName("AI 가 LLM 을 부르지 못해 503 을 돌려주면 다시 시도할 수 있는 AI 를 쓸 수 없음으로 바꾼다")
    void passesLlmUnavailableAsRetryable() {

        responseStatus = 503;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":{},"
                + "\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}";

        assertThatThrownBy(() -> adapter().next(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.isInvalidResponse()).isFalse();
                });

    }

    @Test
    @DisplayName("default_questions 를 읽고, 없으면 false 로 둔다")
    void readsDefaultQuestionsFlagAndDefaultsToFalse() {

        ObjectNode withFlag = callReply();
        withFlag.putArray("questions");
        withFlag.put("default_questions", true);
        responseBody = success(withFlag);
        assertThat(adapter().next(REQUEST).defaultQuestions()).isTrue();

        responseBody = success(callReply());
        assertThat(adapter().next(REQUEST).defaultQuestions()).isFalse();

    }

    private ObjectNode callReply() {

        ObjectNode data = mapper.createObjectNode().put("action", "CALL_TOOL").put("tool", "START_COVER_LETTER")
                .put("posting_ref", "p1");
        data.putNull("find");
        data.putNull("repo_ref");
        data.putNull("job_role");
        data.putNull("say");
        data.putNull("ask_kind");
        data.putArray("options");
        data.putArray("questions").addObject().put("text", "지원 동기를 쓰세요").putNull("char_limit");
        data.withArray("questions").addObject().put("text", "협업 경험을 쓰세요").put("char_limit", 700);
        return data;

    }

    private ObjectNode askReply() {

        ObjectNode data = mapper.createObjectNode().put("action", "ASK_USER").put("say", "어느 레포를 정리할까요?")
                .put("ask_kind", "CHOICE");
        data.putNull("tool");
        data.putNull("find");
        data.putNull("posting_ref");
        data.putNull("repo_ref");
        data.putNull("job_role");
        data.putArray("questions");
        data.putArray("options").add("gitory").add("gitory-docs");
        return data;

    }

    private ObjectNode finishReply() {

        ObjectNode data = mapper.createObjectNode().put("action", "FINISH").put("say", "자소서를 쓰기 시작했어요.");
        data.putNull("tool");
        data.putNull("find");
        data.putNull("posting_ref");
        data.putNull("repo_ref");
        data.putNull("job_role");
        data.putNull("ask_kind");
        data.putArray("questions");
        data.putArray("options");
        return data;

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private AiAssistantStepAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiAssistantStepAdapter(new AiHttpClient(properties, mapper));

    }
}
