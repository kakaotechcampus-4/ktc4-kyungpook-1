package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.StarConfidence;
import com.gitory.backend.agent.port.StarDraft;
import com.gitory.backend.agent.port.StarDraftRequest;
import com.gitory.backend.agent.port.StarEmptyReason;
import com.gitory.backend.agent.port.StarEvidence;
import com.gitory.backend.agent.port.StarEvidenceRef;
import com.gitory.backend.agent.port.StarEvidenceSource;
import com.gitory.backend.agent.port.StarMaterialKind;
import com.gitory.backend.agent.port.StarSlot;
import com.gitory.backend.agent.port.StarSlotDraft;
import com.gitory.backend.agent.port.StarSlotStatus;
import com.gitory.backend.agent.port.StarStep;
import com.gitory.backend.agent.port.StarStepActor;
import com.gitory.backend.agent.port.StarStepKind;
import com.gitory.backend.agent.port.StarSourceType;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

/** 실제 HTTP 를 거쳐 STAR 초안 API 의 요청 모양과 네 칸 확인, 실패 구분을 검사한다 */
class AiStarDraftAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String PATH = "/internal/analysis/star";
    private static final String SHA = "abc1234567890";
    private static final String MESSAGE = "feat: 세션 DB 저장";
    private static final String URL = "https://github.com/grow22/gitory/commit/abc1234567890";
    private static final String TITLE = "세션 저장소를 DB 로 옮김";
    private static final String EMPTY_REASON = "근거 커밋에서 확인되지 않았습니다.";
    private static final String REQUEST_TEXT = "request-only-text";
    private static final String RESPONSE_TEXT = "response-only-text";

    private HttpServer server;
    private AiStarDraftAdapter adapter;
    private volatile String responseBody;
    private volatile String responseContentType = "application/json";
    private volatile int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile boolean cutBody;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestToken;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        responseBody = success(draftData());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            requestToken = exchange.getRequestHeaders().getFirst("X-GitHub-Token");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", responseContentType);
            if (cutBody) {
                writeCutBody(exchange, responseStatus, body);
                return;
            }
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
    @DisplayName("STAR 경로로 diff 근거를 그대로 보내고 토큰은 싣지 않으며, 네 칸 초안을 돌려준다")
    void sendsEvidenceAndReturnsDraft() {

        StarDraft draft = adapter.draft(request());

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo(PATH);
        assertThat(requestToken).isNull();
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {
                  "candidate_id": "cand-1",
                  "target_login": "grow22",
                  "title": "세션 저장소를 DB 로 옮김",
                  "source_type": "PR",
                  "source_ref": "#17",
                  "evidence": [{
                    "sha": "abc1234567890",
                    "message": "feat: 세션 DB 저장",
                    "author_login": "grow22",
                    "churn": "+71/-0",
                    "summary": "request-only-text",
                    "summary_source": "DIFF",
                    "technical_points": ["Spring Session JDBC"],
                    "url": "https://github.com/grow22/gitory/commit/abc1234567890"
                  }]
                }
                """));
        StarEvidenceRef ref = new StarEvidenceRef(SHA, MESSAGE, URL);
        assertThat(draft).isEqualTo(new StarDraft(TITLE, Map.of(
                StarSlot.S, new StarSlotDraft(StarSlotStatus.FILLED, "로그인 세션이 서버 재시작에 사라졌다.",
                        StarConfidence.HIGH, List.of(ref), null, null, null),
                StarSlot.T, new StarSlotDraft(StarSlotStatus.EMPTY, null, null, List.of(), StarEmptyReason.NO_EVIDENCE,
                        EMPTY_REASON, null),
                StarSlot.A, new StarSlotDraft(StarSlotStatus.NEEDS_REVIEW, "세션을 JDBC 저장소로 옮겨 30% 빨라졌다.",
                        StarConfidence.LOW, List.of(ref), null, null, "근거에 없는 수치: 30%"),
                StarSlot.R, new StarSlotDraft(StarSlotStatus.FILLED, "재시작해도 로그인이 유지됐다.",
                        StarConfidence.MEDIUM, List.of(ref), null, null, null)),
                List.of(StarSlot.T), "teammate", List.of("근거 없는 문장"), List.of("BACKEND", "DB"), List.of("JAVA"),
                List.of()));

    }

    @Test
    @DisplayName("바뀐 파일 경로가 있으면 근거마다 changed_paths 로 싣고, 경로 목록이 비어 있으면 STAR 요청에 changed_paths 칸이 없다")
    void sendsChangedPathsOnlyWhenPresent() {

        StarEvidence base = request().evidence().getFirst();
        StarEvidence withPaths = new StarEvidence(base.sha(), base.message(), base.authorLogin(), base.churn(),
                base.summary(), base.summarySource(), base.technicalPoints(), base.url(),
                List.of("src/main/java/SessionConfig.java"));
        StarDraftRequest empty = request();
        adapter.draft(new StarDraftRequest(empty.candidateId(), empty.targetLogin(), empty.title(), empty.sourceType(),
                empty.sourceRef(), List.of(withPaths, base), null));

        JsonNode evidence = MAPPER.readTree(requestBody).path("evidence");
        assertThat(evidence.path(0).path("changed_paths").get(0).asString()).isEqualTo("src/main/java/SessionConfig.java");
        assertThat(evidence.path(1).has("changed_paths")).isFalse();

    }

    @ParameterizedTest
    @MethodSource("withoutTags")
    @DisplayName("태그를 보내기 전의 AI 가 areas·techs 칸을 빼거나 null 로 보내면 빈 목록으로 받는다")
    void readsMissingTagsAsEmpty(ObjectNode data) {

        responseBody = success(data);

        StarDraft draft = adapter.draft(request());

        assertThat(draft.areas()).isEmpty();
        assertThat(draft.techs()).isEmpty();

    }

    @Test
    @DisplayName("AI 가 시간 안에 초안을 못 만들어 네 칸을 모두 TIMEOUT 으로 비운 응답도 받는다")
    void acceptsAllSlotsTimedOut() {

        responseBody = success(draft(data -> {
            for (StarSlot slot : StarSlot.values()) {
                ((ObjectNode) data.path("star")).set(slot.name(), empty("TIMEOUT"));
            }
            data.putArray("missing_fields").add("S").add("T").add("A").add("R");
            data.putNull("shared_with");
            data.putArray("removed_claims");
        }));

        StarDraft draft = adapter.draft(request());

        assertThat(draft.slots().values()).allSatisfy(slot -> {
            assertThat(slot.status()).isEqualTo(StarSlotStatus.EMPTY);
            assertThat(slot.emptyReason()).isEqualTo(StarEmptyReason.TIMEOUT);
        });
        assertThat(draft.missingFields()).containsExactly(StarSlot.S, StarSlot.T, StarSlot.A, StarSlot.R);
        assertThat(draft.sharedWith()).isNull();

    }

    @Test
    @DisplayName("PR 본문이 있으면 pull_request_body 로 싣는다(없으면 키를 싣지 않는 것은 위 시험의 JSON 이 확인한다)")
    void sendsPullRequestBodyWhenPresent() {

        StarDraftRequest base = request();
        adapter.draft(new StarDraftRequest(base.candidateId(), base.targetLogin(), base.title(), base.sourceType(),
                base.sourceRef(), base.evidence(), "## 고민한 점 폴링을 골랐다"));

        assertThat(MAPPER.readTree(requestBody).path("pull_request_body").asString()).isEqualTo("## 고민한 점 폴링을 골랐다");

    }

    @Test
    @DisplayName("초안 단계는 순서대로 읽고, 모르는 값·음수 바퀴·객체가 아닌 단계만 버린 채 카드는 그대로 받는다")
    void readsDraftStepsAndDropsMalformedOnes() {

        responseBody = success(draft(data -> {
            ArrayNode steps = data.putArray("steps");
            steps.addObject().put("round", 0).put("actor", "MODEL").put("kind", "DRAFT");
            steps.addObject().put("round", 1).put("actor", "MODEL").put("kind", "OPEN").put("slot", "S")
                    .put("value", "이슈 #98 세션 만료").put("material", "ISSUE").put("reason", "문제 상황이 적혀 있을 것 같음");
            steps.addObject().put("round", 2).put("actor", "CODE").put("kind", "REVERT").put("slot", "A")
                    .put("code", "UNSOURCED_NUMBER");
            steps.addObject().put("round", 1).put("actor", "MODEL").put("kind", "THINK");
            steps.addObject().put("round", -1).put("actor", "CODE").put("kind", "PASS");
            steps.addObject().put("round", 1).put("actor", "CODE").put("kind", "PASS").put("slot", "X");
            steps.add("문장");
        }));

        StarDraft draft = adapter.draft(request());

        assertThat(draft.title()).isEqualTo(TITLE);
        assertThat(draft.steps()).containsExactly(
                new StarStep(0, StarStepActor.MODEL, StarStepKind.DRAFT, null, null, null, null, null),
                new StarStep(1, StarStepActor.MODEL, StarStepKind.OPEN, StarSlot.S, null, "이슈 #98 세션 만료",
                        StarMaterialKind.ISSUE, "문제 상황이 적혀 있을 것 같음"),
                new StarStep(2, StarStepActor.CODE, StarStepKind.REVERT, StarSlot.A, "UNSOURCED_NUMBER", null, null,
                        null));

    }

    @Test
    @DisplayName("초안 단계가 60개를 넘으면 앞 60개만 받는다")
    void capsDraftSteps() {

        responseBody = success(draft(data -> {
            ArrayNode steps = data.putArray("steps");
            for (int i = 0; i < 70; i++) {
                steps.addObject().put("round", 0).put("actor", "CODE").put("kind", "FLAG").put("code", "C" + i);
            }
        }));

        assertThat(adapter.draft(request()).steps()).hasSize(60).last().extracting(StarStep::code).isEqualTo("C59");

    }

    @ParameterizedTest
    @MethodSource("acceptedVariants")
    @DisplayName("선택 칸이 없거나 null 이고 모르는 칸이 더 있어도 성공으로 받는다")
    void acceptsOptionalAndUnknownFields(ObjectNode data) {

        responseBody = success(data);

        assertThat(adapter.draft(request()).title()).isEqualTo(TITLE);

    }

    @ParameterizedTest
    @MethodSource("malformedDrafts")
    @DisplayName("계약과 다른 응답은 다시 시도할 수 없는 이상한 응답으로 끝난다")
    void rejectsMalformedDraft(ObjectNode data) {

        responseBody = success(data);

        assertFailure(false);

    }

    @Test
    @DisplayName("응답의 글이 든 예외는 원인으로 넘기지 않아, 모르는 칸 값이 예외 메시지로 새지 않는다")
    void doesNotLeakResponseTextThroughTheCause() {

        responseBody = success(slot("S", slot -> slot.put("status", RESPONSE_TEXT)));

        assertFailure(false);

    }

    @Test
    @DisplayName("응답이 STAR 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다")
    void timesOutByStarReadTimeout() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().starReadTimeout(Duration.ofMillis(100)).build());

        assertFailure(true);

    }

    @Test
    @DisplayName("STAR 대기 시간이 기본값이면 다른 대기 시간이 짧아도 응답을 끝까지 기다려 받는다")
    void waitsByStarReadTimeoutOnly() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().readTimeout(Duration.ofMillis(100)).interviewReadTimeout(Duration.ofMillis(100))
                .diffEvidenceReadTimeout(Duration.ofMillis(100)).build());

        assertThat(adapter.draft(request()).title()).isEqualTo(TITLE);

    }

    @Test
    @DisplayName("AI 에 연결할 수 없으면 다시 시도할 수 있는 실패로 끝난다")
    void connectionRefusedIsRetryable() {

        server.stop(0);

        assertFailure(true);

    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 500})
    @DisplayName("응답 헤더 뒤에 본문이 끊기면 상태가 무엇이든 다시 시도할 수 있는 실패로 끝난다")
    void cutBodyIsRetryable(int status) {

        responseStatus = status;
        cutBody = true;

        assertFailure(true);

    }

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 504})
    @DisplayName("AI 가 잠시 받지 못한다는 상태(429·502·503·504)면 다시 시도할 수 있는 실패로 끝난다")
    void transientStatusIsRetryable(int status) {

        responseStatus = status;
        responseContentType = "text/plain";
        responseBody = RESPONSE_TEXT;

        assertFailure(true);

    }

    @ParameterizedTest
    @CsvSource({"503, LLM_UNAVAILABLE, true, true", "503, LLM_UNAVAILABLE, false, false",
            "400, INVALID_PAYLOAD, false, false", "500, INTERNAL_ERROR, true, false"})
    @DisplayName("AI 가 다시 해도 된다고 알린 실패만 다시 시도할 수 있고, 요청이 틀렸거나(400) AI 가 고장 난(500) 실패는 아니다")
    void classifiesEnvelopeFailure(int status, String code, boolean envelopeRetryable, boolean retryable) {

        responseStatus = status;
        responseBody = failure(code, envelopeRetryable);

        assertFailure(retryable);

    }

    static Stream<Arguments> acceptedVariants() {

        return Stream.of(
                argumentSet("shared_with 가 null", draft(data -> data.putNull("shared_with"))),
                argumentSet("shared_with 가 없음", draft(data -> data.remove("shared_with"))),
                argumentSet("확인 필요 칸에 review_reason 이 없음", slot("A", slot -> slot.remove("review_reason"))),
                argumentSet("빈 칸에 insufficient_reason 이 없음", slot("T", slot -> slot.remove("insufficient_reason"))),
                argumentSet("근거 url 이 null", slot("S", slot -> ((ObjectNode) slot.path("evidence").path(0))
                        .putNull("url"))),
                argumentSet("근거 없이 채운 칸", slot("R", slot -> slot.putArray("evidence"))),
                argumentSet("모르는 칸이 더 있음", draft(data -> {
                    data.put("model_note", "보기");
                    ((ObjectNode) data.path("star").path("S")).put("source", "LLM");
                })));

    }

    static Stream<Arguments> malformedDrafts() {

        return Stream.of(
                argumentSet("title 이 없다", draft(data -> data.remove("title"))),
                argumentSet("star 가 없다", draft(data -> data.remove("star"))),
                argumentSet("star 에 R 칸이 없다", draft(data -> ((ObjectNode) data.path("star")).remove("R"))),
                argumentSet("star 에 모르는 칸이 더 있다", draft(data -> ((ObjectNode) data.path("star"))
                        .set("X", empty("NO_EVIDENCE")))),
                argumentSet("칸이 객체가 아니다", draft(data -> ((ObjectNode) data.path("star")).put("S", "채움"))),
                argumentSet("status 가 없다", slot("S", slot -> slot.remove("status"))),
                argumentSet("status 가 모르는 값이다", slot("S", slot -> slot.put("status", "DONE"))),
                argumentSet("채운 칸에 text 가 없다", slot("S", slot -> slot.putNull("text"))),
                argumentSet("채운 칸의 text 가 공백뿐이다", slot("S", slot -> slot.put("text", "   "))),
                argumentSet("채운 칸에 confidence 가 없다", slot("S", slot -> slot.putNull("confidence"))),
                argumentSet("confidence 가 모르는 값이다", slot("S", slot -> slot.put("confidence", "VERY_HIGH"))),
                argumentSet("채운 칸의 confidence 가 LOW 다", slot("S", slot -> slot.put("confidence", "LOW"))),
                argumentSet("확인 필요 칸의 confidence 가 HIGH 다", slot("A", slot -> slot.put("confidence", "HIGH"))),
                argumentSet("채운 칸에 비운 이유 코드가 있다",
                        slot("S", slot -> slot.put("insufficient_reason_code", "NO_EVIDENCE"))),
                argumentSet("빈 칸에 비운 이유 코드가 없다", slot("T", slot -> slot.putNull("insufficient_reason_code"))),
                argumentSet("비운 이유 코드가 모르는 값이다", slot("T", slot -> slot.put("insufficient_reason_code", "LATER"))),
                argumentSet("빈 칸에 text 가 있다", slot("T", slot -> slot.put("text", "지어낸 문장"))),
                argumentSet("빈 칸에 근거가 있다", slot("T", slot -> slot.putArray("evidence").addObject().put("sha", SHA)
                        .put("message", MESSAGE))),
                argumentSet("evidence 가 배열이 아니다", slot("S", slot -> slot.put("evidence", SHA))),
                argumentSet("근거 sha 가 7자보다 짧다", slot("S", slot -> ((ObjectNode) slot.path("evidence").path(0))
                        .put("sha", "abc123"))),
                argumentSet("근거 sha 가 40자보다 길다", slot("S", slot -> ((ObjectNode) slot.path("evidence").path(0))
                        .put("sha", "a".repeat(41)))),
                argumentSet("근거 message 가 없다", slot("S", slot -> ((ObjectNode) slot.path("evidence").path(0))
                        .remove("message"))),
                argumentSet("missing_fields 가 없다", draft(data -> data.remove("missing_fields"))),
                argumentSet("missing_fields 에 모르는 칸이 있다", draft(data -> data.putArray("missing_fields").add("X"))),
                argumentSet("missing_fields 가 빈 칸과 다르다", draft(data -> data.putArray("missing_fields").add("S"))),
                argumentSet("missing_fields 에 빈 칸이 빠졌다", draft(data -> data.putArray("missing_fields"))),
                argumentSet("missing_fields 에 같은 빈 칸이 두 번 있다",
                        draft(data -> data.putArray("missing_fields").add("T").add("T"))),
                argumentSet("빈 칸이 둘인데 missing_fields 에 한 칸만 두 번 적었다", draft(data -> {
                    ((ObjectNode) data.path("star")).set("S", empty("NO_EVIDENCE"));
                    data.putArray("missing_fields").add("T").add("T");
                })),
                argumentSet("shared_with 가 글이 아니다", draft(data -> data.put("shared_with", 1))),
                argumentSet("removed_claims 가 없다", draft(data -> data.remove("removed_claims"))),
                argumentSet("removed_claims 에 글이 아닌 값이 있다", draft(data -> data.putArray("removed_claims").add(1))),
                argumentSet("areas 가 배열이 아니다", draft(data -> data.put("areas", "BACKEND"))),
                argumentSet("techs 에 글이 아닌 값이 있다", draft(data -> data.putArray("techs").add(1))));

    }

    static Stream<Arguments> withoutTags() {

        return Stream.of(
                argumentSet("areas·techs 가 없다", draft(data -> {
                    data.remove("areas");
                    data.remove("techs");
                })),
                argumentSet("areas·techs 가 null 이다", draft(data -> data.putNull("areas").putNull("techs"))));

    }

    private static ObjectNode draftData() {

        ObjectNode data = MAPPER.createObjectNode().put("title", TITLE);
        ObjectNode star = data.putObject("star");
        star.set("S", filled("FILLED", "HIGH", "로그인 세션이 서버 재시작에 사라졌다."));
        star.set("T", empty("NO_EVIDENCE"));
        star.set("A", filled("NEEDS_REVIEW", "LOW", "세션을 JDBC 저장소로 옮겨 30% 빨라졌다.")
                .put("review_reason", "근거에 없는 수치: 30%"));
        star.set("R", filled("FILLED", "MEDIUM", "재시작해도 로그인이 유지됐다."));
        data.putArray("missing_fields").add("T");
        data.put("shared_with", "teammate");
        data.putArray("removed_claims").add("근거 없는 문장");
        data.putArray("areas").add("BACKEND").add("DB");
        data.putArray("techs").add("JAVA");
        return data;

    }

    /** AI 가 채우거나 확인 필요로 둔 칸처럼 빈 이유 칸을 null 로 함께 보낸다 */
    private static ObjectNode filled(String status, String confidence, String text) {

        ObjectNode slot = MAPPER.createObjectNode().put("text", text).put("status", status).put("confidence", confidence);
        slot.putArray("evidence").addObject().put("sha", SHA).put("message", MESSAGE).put("url", URL);
        return slot.putNull("insufficient_reason").putNull("insufficient_reason_code").putNull("review_reason");

    }

    private static ObjectNode empty(String code) {

        ObjectNode slot = MAPPER.createObjectNode().putNull("text").put("status", "EMPTY").putNull("confidence");
        slot.putArray("evidence");
        return slot.put("insufficient_reason", EMPTY_REASON).put("insufficient_reason_code", code)
                .putNull("review_reason");

    }

    private static ObjectNode draft(Consumer<ObjectNode> edit) {

        ObjectNode data = draftData();
        edit.accept(data);
        return data;

    }

    private static ObjectNode slot(String name, Consumer<ObjectNode> edit) {

        return draft(data -> edit.accept((ObjectNode) data.path("star").path(name)));

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

    private static StarDraftRequest request() {

        return new StarDraftRequest("cand-1", "grow22", TITLE, StarSourceType.PR, "#17",
                List.of(new StarEvidence(SHA, MESSAGE, "grow22", "+71/-0", REQUEST_TEXT, StarEvidenceSource.DIFF,
                        List.of("Spring Session JDBC"), URL, List.of())), null);

    }

    private AiTestProperties properties() {

        return AiTestProperties.defaults().baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

    }

    private AiStarDraftAdapter adapterOf(AiClientProperties properties) {

        return new AiStarDraftAdapter(new AiHttpClient(properties, MAPPER), properties);

    }

    /** 약속한 길이보다 적게 쓰고 닫아 응답 헤더 뒤에 본문이 끊기게 한다 */
    private static void writeCutBody(HttpExchange exchange, int status, byte[] body) throws IOException {

        exchange.sendResponseHeaders(status, body.length + 100L);
        exchange.getResponseBody().write(body, 0, body.length / 2);
        exchange.close();

    }

    /** 두 구분 중 하나만 참이고, 요청·응답의 글은 예외 메시지와 원인 어디에도 없다 */
    private void assertFailure(boolean retryable) {

        assertThatThrownBy(() -> adapter.draft(request()))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isEqualTo(retryable);
                    assertThat(failure.isInvalidResponse()).isEqualTo(!retryable);
                    for (Throwable chain = failure; chain != null; chain = chain.getCause()) {
                        assertThat(String.valueOf(chain.getMessage())).doesNotContain(REQUEST_TEXT, RESPONSE_TEXT);
                    }
                });

    }
}
