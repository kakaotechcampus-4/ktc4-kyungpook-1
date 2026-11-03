package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CoverLetterDrafts;
import com.gitory.backend.agent.port.CoverLetterPolish;
import com.gitory.backend.agent.port.CoverLetterPolishRequest;
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

class AiCoverLetterPolishAdapterTest {

    private static final CoverLetterPolishRequest REQUEST = new CoverLetterPolishRequest(7L, "백엔드 개발", null, null,
            List.of(new CoverLetterPolishRequest.Other(2, "지원 동기를 쓰세요", "결제를 다뤄 보고 싶다.")),
            new CoverLetterPolishRequest.Target(1, "협업 경험을 쓰세요", 700, "COLLABORATION",
                    new CoverLetterQuestionsRequest.CardDetail(11L, "배포 시간 단축", "TECH", null,
                            List.of(new CoverLetterQuestionsRequest.Statement("A", "빌드를 병렬로 돌렸다"))),
                    null,
                    List.of(new CoverLetterQuestionsRequest.KnownAnswer("RESULT", "배포가 3분 빨라졌다", 11L)),
                    "배포를 함께 고쳤다. 팀이 기뻐했다.", List.of(2)));

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
    @DisplayName("첨삭 API 에 다른 문항의 지금 글과 대상 문항의 지금 글·확인하지 않은 추론 번호를 snake_case 로 보내고, 종합 평가·포인트·고친 글을 돌려받는다")
    void postsSnakeCaseRequestAndReadsPolish() {

        CoverLetterPolish result = adapter().polish(REQUEST);

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/cover-letter-polish");
        assertThat(sent.path("cover_letter_id").asLong()).isEqualTo(7L);
        assertThat(sent.has("posting")).isFalse();
        assertThat(sent.path("others").get(0).path("question_id").asInt()).isEqualTo(2);
        assertThat(sent.path("others").get(0).path("current_text").asString()).isEqualTo("결제를 다뤄 보고 싶다.");
        assertThat(sent.path("target").path("char_limit").asInt()).isEqualTo(700);
        assertThat(sent.path("target").path("question_theme").asString()).isEqualTo("COLLABORATION");
        assertThat(sent.path("target").path("primary_card").path("card_id").asLong()).isEqualTo(11L);
        assertThat(sent.path("target").path("supporting_card").isNull()).isTrue();
        assertThat(sent.path("target").path("answers").get(0).path("card_id").asLong()).isEqualTo(11L);
        assertThat(sent.path("target").path("current_text").asString()).isEqualTo("배포를 함께 고쳤다. 팀이 기뻐했다.");
        assertThat(sent.path("target").path("inferred_seqs").get(0).asInt()).isEqualTo(2);
        assertThat(sent.path("target").has("confirmed_inferred_seqs")).isFalse();
        assertThat(result).isEqualTo(new CoverLetterPolish("과정이 잘 보입니다.",
                List.of(new CoverLetterPolish.Point(1, "WEAK", "EVIDENCE", "결과가 안 보여요", "달라진 점을 적어요", List.of(2)),
                        new CoverLetterPolish.Point(2, "GOOD", "FIT", "끝까지 파고들어요", "문항과 맞아요", List.of())),
                new CoverLetterDrafts.Draft(1, "배포를 함께 고쳤다. 배포가 3분 빨라졌다.",
                        List.of(new CoverLetterDrafts.Sentence(1, "배포를 함께 고쳤다."),
                                new CoverLetterDrafts.Sentence(2, "배포가 3분 빨라졌다.")),
                        24, 0, List.of(new CoverLetterDrafts.Mark(1, "INFERRED", null)), List.of("LEARNING"), "LLM")));

    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownKind", "unknownCategory", "pointOnMissingSentence", "zeroSentenceSeq",
            "tooManyWeak", "tooManyGood", "duplicatePointSeq", "blankSummary", "blankTitle", "otherQuestionDraft",
            "unknownMarkCode", "markOnMissingSentence", "unknownStepKind", "otherQuestionStep", "missingDraft"})
    @DisplayName("모르는 분류·종류, 고친 글에 없는 문장을 가리킨 포인트, 아쉬운 점 5개·잘한 점 4개, 겹친 포인트 번호, 빈 글, 다른 문항의 글·과정, 초안 쓰기 허용 목록 밖의 표시·과정이 온 응답은 다시 해도 같은 이상 응답이다")
    void rejectsResponsesOutsideTheContract(String broken) {

        ObjectNode data = validData();
        ObjectNode first = (ObjectNode) data.withArray("points").get(0);
        ObjectNode draft = (ObjectNode) data.path("draft");
        switch (broken) {
            case "unknownKind" -> first.put("kind", "NEUTRAL");
            case "unknownCategory" -> first.put("category", "GRAMMAR");
            case "pointOnMissingSentence" -> first.withArray("sentence_seqs").add(3);
            case "zeroSentenceSeq" -> first.withArray("sentence_seqs").add(0);
            case "tooManyWeak" -> addPoints(data, "WEAK", 4);
            case "tooManyGood" -> addPoints(data, "GOOD", 3);
            case "duplicatePointSeq" -> ((ObjectNode) data.withArray("points").get(1)).put("seq", 1);
            case "blankSummary" -> data.put("summary", " ");
            case "blankTitle" -> first.put("title", "");
            case "otherQuestionDraft" -> draft.put("question_id", 2);
            case "unknownMarkCode" -> ((ObjectNode) draft.withArray("marks").get(0)).put("code", "OVER_LIMIT");
            case "markOnMissingSentence" -> ((ObjectNode) draft.withArray("marks").get(0)).put("sentence_seq", 3);
            case "unknownStepKind" -> ((ObjectNode) data.withArray("steps").get(0)).put("kind", "POLISHED");
            case "otherQuestionStep" -> ((ObjectNode) data.withArray("steps").get(0)).put("question_id", 2);
            case "missingDraft" -> data.remove("draft");
            default -> throw new IllegalArgumentException(broken);
        }
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().polish(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isFalse();
                    assertThat(failure.isInvalidResponse()).isTrue();
                });

    }

    @Test
    @DisplayName("AI 가 LLM 을 부르지 못해 503 을 돌려주면 다시 시도할 수 있는 실패로 넘긴다")
    void passesLlmUnavailableAsRetryable() {

        responseStatus = 503;
        responseBody = "{\"success\":false,\"data\":null,\"meta\":{},"
                + "\"error\":{\"code\":\"LLM_UNAVAILABLE\",\"message\":\"x\",\"retryable\":true}}";

        assertThatThrownBy(() -> adapter().polish(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.isInvalidResponse()).isFalse();
                });

    }

    private static void addPoints(ObjectNode data, String kind, int count) {

        for (int seq = 3; seq < 3 + count; seq++) {
            data.withArray("points").addObject().put("seq", seq).put("kind", kind).put("category", "READABILITY")
                    .put("title", "제목").put("detail", "설명").putArray("sentence_seqs");
        }

    }

    private ObjectNode validData() {

        ObjectNode data = mapper.createObjectNode().put("summary", "과정이 잘 보입니다.");
        data.withArray("points").addObject().put("seq", 1).put("kind", "WEAK").put("category", "EVIDENCE")
                .put("title", "결과가 안 보여요").put("detail", "달라진 점을 적어요").withArray("sentence_seqs").add(2);
        data.withArray("points").addObject().put("seq", 2).put("kind", "GOOD").put("category", "FIT")
                .put("title", "끝까지 파고들어요").put("detail", "문항과 맞아요").putArray("sentence_seqs");
        ObjectNode draft = data.putObject("draft").put("question_id", 1).put("text", "배포를 함께 고쳤다. 배포가 3분 빨라졌다.")
                .put("char_count", 24).put("over_by", 0).put("rewrites", 1).put("source", "LLM");
        draft.withArray("sentences").addObject().put("seq", 1).put("text", "배포를 함께 고쳤다.");
        draft.withArray("sentences").addObject().put("seq", 2).put("text", "배포가 3분 빨라졌다.");
        draft.withArray("marks").addObject().put("sentence_seq", 1).put("code", "INFERRED").putNull("value");
        draft.withArray("blanks").add("LEARNING");
        data.withArray("steps").addObject().put("seq", 1).put("actor", "SUB_AGENT").put("kind", "REWROTE")
                .put("question_id", 1).put("value", "24");
        return data;

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private AiCoverLetterPolishAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiCoverLetterPolishAdapter(new AiHttpClient(properties, mapper), properties);

    }
}
