package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.InterviewAnswerMode;
import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.ExperienceTheme;
import com.gitory.backend.agent.port.InterviewCandidate;
import com.gitory.backend.agent.port.InterviewCardKind;
import com.gitory.backend.agent.port.InterviewCardSourceType;
import com.gitory.backend.agent.port.InterviewCommit;
import com.gitory.backend.agent.port.InterviewConfidence;
import com.gitory.backend.agent.port.InterviewIssue;
import com.gitory.backend.agent.port.InterviewMissingSlot;
import com.gitory.backend.agent.port.InterviewNextAction;
import com.gitory.backend.agent.port.InterviewQuestionRequest;
import com.gitory.backend.agent.port.InterviewTurnOutcome;
import com.gitory.backend.agent.port.InterviewPreviousTurn;
import com.gitory.backend.agent.port.InterviewQuestionResult;
import com.gitory.backend.agent.port.InterviewQuestionType;
import com.gitory.backend.agent.port.InterviewReview;
import com.gitory.backend.agent.port.InterviewReviewState;
import com.gitory.backend.agent.port.InterviewStarSlot;
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
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

/** 실제 HTTP 를 거쳐 되묻기 질문 API 의 요청 모양과 응답 확인, 실패 구분을 검사한다 */
class AiInterviewQuestionAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String PATH = "/internal/interview-turns";
    private static final long CARD_ID = 104;
    private static final String QUESTION = "커밋 abc1234 작업 이후 확인된 결과가 있었나요?";
    private static final String REQUEST_TEXT = "request-only-text";
    private static final String RESPONSE_TEXT = "response-only-text";

    private HttpServer server;
    private AiInterviewQuestionAdapter adapter;
    private volatile String responseBody;
    private volatile String responseContentType = "application/json";
    private volatile int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile boolean cutBody;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestContentType;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws Exception {

        responseBody = success(askAgainData());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
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
    @DisplayName("질문이 있으면 되묻기 경로로 JSON 을 보내고 대상 칸·질문 종류·질문 글을 돌려준다")
    void asksAndReturnsTheQuestion() {

        InterviewQuestionResult result = adapter.nextQuestion(request());

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo(PATH);
        assertThat(requestContentType).startsWith("application/json");
        assertThat(result).isEqualTo(new InterviewQuestionResult(InterviewNextAction.ASK_AGAIN,
                InterviewStarSlot.R, 2, InterviewQuestionType.EVIDENCE_GAP, QUESTION, InterviewAnswerMode.TEXT, List.of(), null, List.of()));

    }

    @ParameterizedTest
    @MethodSource("completeBodies")
    @DisplayName("더 물을 것이 없으면 질문 칸이 비었거나 없는 COMPLETE 를 질문 없이 돌려준다")
    void returnsCompleteWithoutQuestion(ObjectNode data) {

        responseBody = success(data);

        InterviewQuestionResult result = adapter.nextQuestion(request());

        assertThat(result).isEqualTo(new InterviewQuestionResult(InterviewNextAction.COMPLETE, null, null, null, null, null, List.of(), null, List.of()));

    }

    @ParameterizedTest
    @MethodSource("triggerSources")
    @DisplayName("trigger_source 가 null·모르는 값·없음이어도 모르는 응답 칸(found·missing·options)과 함께 성공으로 받는다")
    void ignoresTriggerSourceAndUnknownFields(ObjectNode data) {

        responseBody = success(withUnknownFields(data));

        InterviewQuestionResult result = adapter.nextQuestion(request());

        assertThat(result).isEqualTo(new InterviewQuestionResult(InterviewNextAction.ASK_AGAIN,
                InterviewStarSlot.R, 2, InterviewQuestionType.EVIDENCE_GAP, QUESTION, InterviewAnswerMode.TEXT, List.of(), null, List.of()));

    }

    @ParameterizedTest
    @MethodSource("completeTriggerSources")
    @DisplayName("COMPLETE 도 trigger_source 가 null·AUTO·모르는 값·없음이어도 모르는 응답 칸(found·missing·options)과 함께 성공으로 받는다")
    void ignoresTriggerSourceAndUnknownFieldsOnComplete(ObjectNode data) {

        responseBody = success(withUnknownFields(data));

        InterviewQuestionResult result = adapter.nextQuestion(request());

        assertThat(result).isEqualTo(new InterviewQuestionResult(InterviewNextAction.COMPLETE, null, null, null, null, null, List.of(), null, List.of()));

    }

    @Test
    @DisplayName("COMPLETE 의 문항 유형은 모르는 값·겹친 값을 버리고 앞에서부터 두 개까지 받고, 질문에서는 받지 않는다")
    void readsThemesOnCompleteLeniently() {

        responseBody = success(complete(data -> data.putArray("themes").add("GROWTH").add("SINGING").add("GROWTH")
                .add(3).add("CONFLICT").add("LEADERSHIP")));
        InterviewQuestionResult ended = adapter.nextQuestion(request());
        responseBody = success(askAgain(data -> data.putArray("themes").add("GROWTH")));
        InterviewQuestionResult asked = adapter.nextQuestion(request());

        assertThat(ended.themes()).containsExactly(ExperienceTheme.GROWTH, ExperienceTheme.CONFLICT);
        assertThat(asked.themes()).isEmpty();

    }

    @Test
    @DisplayName("질문과 COMPLETE 모두 AI 가 쓴 판단 이유를 앞뒤 공백만 떼고 받는다")
    void readsReason() {

        responseBody = success(askAgain(data -> data.put("reason", "  결과에 수치가 없어 물어요 ")));
        InterviewQuestionResult asked = adapter.nextQuestion(request());
        responseBody = success(complete(data -> data.put("reason", "네 칸 모두 근거가 있어 마칠게요")));
        InterviewQuestionResult ended = adapter.nextQuestion(request());

        assertThat(asked.reason()).isEqualTo("결과에 수치가 없어 물어요");
        assertThat(ended.reason()).isEqualTo("네 칸 모두 근거가 있어 마칠게요");

    }

    @Test
    @DisplayName("판단 이유가 글이 아니거나 비었으면 버리고 질문은 그대로 받으며, 60자를 넘으면 저장 칸 길이에서 자른다")
    void dropsMalformedReasonAndClipsLongOne() {

        responseBody = success(askAgain(data -> data.put("reason", 3)));
        InterviewQuestionResult number = adapter.nextQuestion(request());
        responseBody = success(askAgain(data -> data.put("reason", "   ")));
        InterviewQuestionResult blank = adapter.nextQuestion(request());
        responseBody = success(askAgain(data -> data.put("reason", "가".repeat(80))));
        InterviewQuestionResult longer = adapter.nextQuestion(request());

        assertThat(number.questionText()).isEqualTo(QUESTION);
        assertThat(number.reason()).isNull();
        assertThat(blank.reason()).isNull();
        assertThat(longer.reason()).isEqualTo("가".repeat(60));

    }

    @ParameterizedTest
    @MethodSource("malformedAskAgain")
    @DisplayName("계약과 다른 응답은 다시 시도할 수 없는 이상한 응답으로 끝난다")
    void rejectsMalformedResponse(ObjectNode data) {

        responseBody = success(data);

        assertFailure(false);

    }

    @ParameterizedTest
    @MethodSource("malformedComplete")
    @DisplayName("COMPLETE 모양이 어긋난 응답(질문 칸이 차 있거나 next_action 이 모르는 값)은 이상한 응답으로 끝난다")
    void rejectsMalformedComplete(ObjectNode data) {

        responseBody = success(data);

        assertFailure(false);

    }

    @ParameterizedTest
    @MethodSource("completeWithWrongCardId")
    @DisplayName("질문이 없는 COMPLETE 응답도 card_id 가 요청과 다르거나 정수가 아니면 이상한 응답으로 끝난다")
    void rejectsCompleteWithWrongCardId(ObjectNode data) {

        responseBody = success(data);

        assertFailure(false);

    }

    @Test
    @DisplayName("응답의 글이 든 예외는 원인으로 넘기지 않아, 모르는 칸 값이 예외 메시지로 새지 않는다")
    void doesNotLeakResponseTextThroughTheCause() {

        responseBody = success(askAgain(data -> data.put("target_star_slot", RESPONSE_TEXT)));

        assertFailure(false);

    }

    @Test
    @DisplayName("JSON 이 아닌 성공 응답은 이상한 응답으로 끝난다")
    void rejectsNonJsonSuccessBody() {

        responseBody = RESPONSE_TEXT;

        assertFailure(false);

    }

    @Test
    @DisplayName("응답이 되묻기 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다 — 다른 대기 시간이 길어도 되묻기 값만 따른다")
    void timesOutByInterviewReadTimeoutOnly() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().interviewReadTimeout(Duration.ofMillis(100)).build());

        assertFailure(true);

    }

    @Test
    @DisplayName("되묻기 대기 시간이 기본값이면 다른 대기 시간이 짧아도 응답을 끝까지 기다려 받는다")
    void waitsByInterviewReadTimeoutOnly() {

        responseDelayMs = 600;
        adapter = adapterOf(properties().readTimeout(Duration.ofMillis(100)).collectReadTimeout(Duration.ofMillis(100))
                .groupsReadTimeout(Duration.ofMillis(100)).build());

        assertThat(adapter.nextQuestion(request()).nextAction()).isEqualTo(InterviewNextAction.ASK_AGAIN);

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
    @CsvSource({"200, LLM_UNAVAILABLE", "429, LLM_RATE_LIMIT", "502, GITHUB_API_ERROR", "503, LLM_UNAVAILABLE"})
    @DisplayName("다시 시도할 수 있다고 알린 오류 봉투는 다시 시도할 수 있는 실패로 끝난다")
    void retryableEnvelopeIsRetryable(int status, String code) {

        responseStatus = status;
        responseBody = failure(code, true);

        assertFailure(true);

    }

    @ParameterizedTest
    @CsvSource({"400, INVALID_PAYLOAD, false", "400, INVALID_PAYLOAD, true", "500, INTERNAL_ERROR, false",
            "500, INTERNAL_ERROR, true", "500, LLM_UNAVAILABLE, true", "404, RESOURCE_NOT_FOUND, false",
            "401, UNAUTHORIZED, false", "503, LLM_UNAVAILABLE, false"})
    @DisplayName("요청이 틀렸거나(400) AI 가 고장 났거나(500) 다시 해도 소용없다는 오류 봉투는 이상한 응답으로 끝난다")
    void brokenEnvelopeIsInvalid(int status, String code, boolean retryable) {

        responseStatus = status;
        responseBody = failure(code, retryable);

        assertFailure(false);

    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422, 500})
    @DisplayName("봉투 없이 글자만 온 400·404·422·500 응답은 이상한 응답으로 끝난다")
    void brokenPlainStatusIsInvalid(int status) {

        responseStatus = status;
        responseContentType = "text/plain";
        responseBody = RESPONSE_TEXT;

        assertFailure(false);

    }

    @Test
    @DisplayName("공유 계약 사례(직접 작성 카드의 T칸)의 요청과 같은 JSON 을 보낸다 — 후보와 본문은 null 로 있다")
    void sendsTheSharedContractRequest() {

        JsonNode expected = MAPPER.readTree(Path.of("../contracts/interview/direct-card.json"))
                .path("springToAi").path("expectedRequest");
        responseBody = success(askAgain(data -> data.put("target_star_slot", "T").put("target_statement_seq", 1)));
        InterviewQuestionRequest request = new InterviewQuestionRequest(104, null,
                List.of(new InterviewMissingSlot(InterviewStarSlot.S, 1, "S 칸에 이미 쓴 글", InterviewConfidence.HIGH, List.of()),
                        new InterviewMissingSlot(InterviewStarSlot.T, 1, null, InterviewConfidence.LOW, List.of()),
                        new InterviewMissingSlot(InterviewStarSlot.A, 1, "A 칸에 이미 쓴 글", InterviewConfidence.HIGH, List.of()),
                        new InterviewMissingSlot(InterviewStarSlot.R, 1, "R 칸에 이미 쓴 글", InterviewConfidence.HIGH, List.of())),
                0, 3, InterviewCardSourceType.DIRECT_CARD, InterviewCardKind.QUALITATIVE, InterviewStarSlot.T,
                List.of(InterviewStarSlot.S, InterviewStarSlot.T, InterviewStarSlot.A, InterviewStarSlot.R), List.of());

        adapter.nextQuestion(request);

        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(expected);

    }

    @Test
    @DisplayName("후보 문맥·지정 칸·고를 수 있는 칸·지난 질문을 채우면 AI 요청 스키마의 키 이름과 중첩 그대로 보낸다")
    void sendsFilledCandidateWithTheAiKeys() {

        InterviewCandidate candidate = new InterviewCandidate(null,
                List.of(new InterviewCommit(11, "abc1234", "feat: 인덱스 추가")),
                List.of(new InterviewReview(21, 7, InterviewReviewState.CHANGES_REQUESTED, "쿼리 수를 줄여 주세요")),
                List.of(new InterviewIssue(3, "목록이 느리다", null)));
        InterviewMissingSlot slot = new InterviewMissingSlot(InterviewStarSlot.R, 2, "응답이 빨라졌다",
                InterviewConfidence.MEDIUM, List.of(new InterviewCommit(12, "def5678", "perf: 캐시 추가")));

        adapter.nextQuestion(new InterviewQuestionRequest(CARD_ID, candidate, List.of(slot), 1, 2,
                InterviewCardSourceType.COMMIT_CLUSTER, InterviewCardKind.TECH, InterviewStarSlot.R, List.of(InterviewStarSlot.R),
                List.of(new InterviewPreviousTurn(InterviewStarSlot.S, "상황은요?", InterviewTurnOutcome.ANSWERED, "느렸다"))));

        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {
                  "card_id": 104,
                  "candidate": {
                    "github_pr_number": null,
                    "commits": [{"commit_id": 11, "sha": "abc1234", "message": "feat: 인덱스 추가"}],
                    "reviews": [{"review_id": 21, "pr_number": 7, "state": "CHANGES_REQUESTED",
                                 "summary": "쿼리 수를 줄여 주세요"}],
                    "issues": [{"issue_number": 3, "title": "목록이 느리다", "summary": null}]
                  },
                  "missing_slots": [{
                    "star_slot": "R",
                    "statement_seq": 2,
                    "body": "응답이 빨라졌다",
                    "confidence": "MEDIUM",
                    "linked_commits": [{"commit_id": 12, "sha": "def5678", "message": "perf: 캐시 추가"}]
                  }],
                  "existing_turn_count": 1,
                  "max_turns": 2,
                  "source_type": "COMMIT_CLUSTER",
                  "card_kind": "TECH",
                  "target_star_slot": "R",
                  "allowed_slots": ["R"],
                  "previous_turns": [{"star_slot": "S", "question_text": "상황은요?", "outcome": "ANSWERED",
                                      "answer_text": "느렸다"}]
                }
                """));

    }

    @Test
    @DisplayName("BIGINT 범위의 카드·커밋·리뷰 id 는 줄이지 않고 보내고, 응답의 카드 id 도 같은 범위로 맞춰 본다")
    void keepsBigintIds() {

        long cardId = 5_000_000_000L;
        responseBody = success(askAgain(data -> data.put("card_id", cardId)));
        InterviewCandidate candidate = new InterviewCandidate(null,
                List.of(new InterviewCommit(6_000_000_000L, "abc1234", "feat: 인덱스 추가")),
                List.of(new InterviewReview(7_000_000_000L, 7, InterviewReviewState.COMMENTED, "리뷰")), List.of());

        adapter.nextQuestion(new InterviewQuestionRequest(cardId, candidate, List.of(), 0, 2,
                InterviewCardSourceType.COMMIT_CLUSTER, InterviewCardKind.TECH, null, List.of(), List.of()));

        JsonNode sent = MAPPER.readTree(requestBody);
        assertThat(sent.path("card_id").longValue()).isEqualTo(cardId);
        assertThat(sent.path("candidate").path("commits").path(0).path("commit_id").longValue())
                .isEqualTo(6_000_000_000L);
        assertThat(sent.path("candidate").path("reviews").path(0).path("review_id").longValue())
                .isEqualTo(7_000_000_000L);

    }

    static Stream<Arguments> completeBodies() {

        return Stream.of(
                argumentSet("질문 칸이 모두 null", completeData()),
                argumentSet("질문 칸이 모두 없음", MAPPER.createObjectNode().put("card_id", CARD_ID)
                        .put("next_action", "COMPLETE")));

    }

    static Stream<Arguments> triggerSources() {

        return Stream.of(
                argumentSet("trigger_source 가 null", askAgain(data -> data.putNull("trigger_source"))),
                argumentSet("trigger_source 가 AI 계약에 없는 값", askAgain(data -> data.put("trigger_source", "USER"))),
                argumentSet("trigger_source 가 없음", askAgain(data -> data.remove("trigger_source"))));

    }

    static Stream<Arguments> completeTriggerSources() {

        return Stream.of(
                argumentSet("trigger_source 가 null", complete(data -> data.putNull("trigger_source"))),
                argumentSet("trigger_source 가 AUTO", complete(data -> data.put("trigger_source", "AUTO"))),
                argumentSet("trigger_source 가 AI 계약에 없는 값", complete(data -> data.put("trigger_source", "USER"))),
                argumentSet("trigger_source 가 없음", complete(data -> data.remove("trigger_source"))));

    }

    static Stream<Arguments> malformedAskAgain() {

        return Stream.of(
                argumentSet("card_id 가 요청보다 크다", askAgain(data -> data.put("card_id", 999))),
                argumentSet("card_id 가 요청보다 작다", askAgain(data -> data.put("card_id", 103))),
                argumentSet("card_id 가 없다", askAgain(data -> data.remove("card_id"))),
                argumentSet("card_id 가 숫자가 아니라 글이다", askAgain(data -> data.put("card_id", "104"))),
                argumentSet("card_id 가 정수가 아니라 소수다", askAgain(data -> data.put("card_id", 104.0))),
                argumentSet("card_id 가 long 범위를 넘는다",
                        askAgain(data -> data.put("card_id", new BigInteger("18446744073709551720")))),
                argumentSet("next_action 이 없다", askAgain(data -> data.remove("next_action"))),
                argumentSet("next_action 이 모르는 값이다", askAgain(data -> data.put("next_action", "WAIT"))),
                argumentSet("target_star_slot 이 없다", askAgain(data -> data.remove("target_star_slot"))),
                argumentSet("target_star_slot 이 null 이다", askAgain(data -> data.putNull("target_star_slot"))),
                argumentSet("target_star_slot 이 S·T·A·R 이 아니다", askAgain(data -> data.put("target_star_slot", "X"))),
                argumentSet("target_statement_seq 가 없다", askAgain(data -> data.remove("target_statement_seq"))),
                argumentSet("target_statement_seq 가 0 이다", askAgain(data -> data.put("target_statement_seq", 0))),
                argumentSet("target_statement_seq 가 음수다", askAgain(data -> data.put("target_statement_seq", -1))),
                argumentSet("target_statement_seq 가 글이다", askAgain(data -> data.put("target_statement_seq", "2"))),
                argumentSet("target_statement_seq 가 소수다", askAgain(data -> data.put("target_statement_seq", 1.5))),
                argumentSet("target_statement_seq 가 소수점 표기다", askAgain(data -> data.put("target_statement_seq", 2.0))),
                argumentSet("target_statement_seq 가 int 범위를 넘는다",
                        askAgain(data -> data.put("target_statement_seq", 4_294_967_297L))),
                argumentSet("question_type 이 없다", askAgain(data -> data.remove("question_type"))),
                argumentSet("question_type 이 모르는 값이다", askAgain(data -> data.put("question_type", "OTHER"))),
                argumentSet("question_text 가 없다", askAgain(data -> data.remove("question_text"))),
                argumentSet("question_text 가 null 이다", askAgain(data -> data.putNull("question_text"))),
                argumentSet("question_text 가 비었다", askAgain(data -> data.put("question_text", ""))),
                argumentSet("question_text 가 공백뿐이다", askAgain(data -> data.put("question_text", "   "))));

    }

    static Stream<Arguments> malformedComplete() {

        return Stream.of(
                argumentSet("next_action 이 모르는 값이고 질문 칸은 비었다", complete(data -> data.put("next_action", "WAIT"))),
                argumentSet("next_action 이 없고 질문 칸은 비었다", complete(data -> data.remove("next_action"))),
                argumentSet("target_star_slot 이 차 있다", complete(data -> data.put("target_star_slot", "T"))),
                argumentSet("target_statement_seq 가 차 있다", complete(data -> data.put("target_statement_seq", 1))),
                argumentSet("question_type 이 차 있다", complete(data -> data.put("question_type", "RECALL_AID"))),
                argumentSet("question_text 가 차 있다", complete(data -> data.put("question_text", QUESTION))));

    }

    static Stream<Arguments> completeWithWrongCardId() {

        return Stream.of(
                argumentSet("card_id 가 요청보다 크다", complete(data -> data.put("card_id", 999))),
                argumentSet("card_id 가 요청보다 작다", complete(data -> data.put("card_id", 103))),
                argumentSet("card_id 가 없다", complete(data -> data.remove("card_id"))),
                argumentSet("card_id 가 숫자가 아니라 글이다", complete(data -> data.put("card_id", "104"))),
                argumentSet("card_id 가 정수가 아니라 소수다", complete(data -> data.put("card_id", 104.0))),
                argumentSet("card_id 가 long 범위를 넘는다",
                        complete(data -> data.put("card_id", new BigInteger("18446744073709551720")))));

    }

    private static ObjectNode askAgainData() {

        return MAPPER.createObjectNode().put("card_id", CARD_ID).put("target_star_slot", "R")
                .put("target_statement_seq", 2).put("question_type", "EVIDENCE_GAP").put("trigger_source", "AUTO")
                .put("question_text", QUESTION).put("next_action", "ASK_AGAIN");

    }

    private static ObjectNode completeData() {

        return MAPPER.createObjectNode().put("card_id", CARD_ID).putNull("target_star_slot")
                .putNull("target_statement_seq").putNull("question_type").putNull("trigger_source")
                .putNull("question_text").put("next_action", "COMPLETE");

    }

    private static ObjectNode askAgain(Consumer<ObjectNode> edit) {

        ObjectNode data = askAgainData();
        edit.accept(data);
        return data;

    }

    private static ObjectNode complete(Consumer<ObjectNode> edit) {

        ObjectNode data = completeData();
        edit.accept(data);
        return data;

    }

    private static ObjectNode withUnknownFields(ObjectNode data) {

        data.putArray("found").add("커밋 abc1234");
        data.putArray("missing");
        data.putArray("options").addObject().put("text", "보기");
        return data;

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

    private static InterviewQuestionRequest request() {

        return new InterviewQuestionRequest(CARD_ID, null,
                List.of(new InterviewMissingSlot(InterviewStarSlot.R, 2, REQUEST_TEXT, InterviewConfidence.MEDIUM,
                        List.of())),
                0, 2, InterviewCardSourceType.DIRECT_CARD, InterviewCardKind.QUALITATIVE, null, List.of(), List.of());

    }

    private AiTestProperties properties() {

        return AiTestProperties.defaults().baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

    }

    private AiInterviewQuestionAdapter adapterOf(AiClientProperties properties) {

        return new AiInterviewQuestionAdapter(new AiHttpClient(properties, MAPPER), properties);

    }

    /** 약속한 길이보다 적게 쓰고 닫아 응답 헤더 뒤에 본문이 끊기게 한다 */
    private static void writeCutBody(HttpExchange exchange, int status, byte[] body) throws IOException {

        exchange.sendResponseHeaders(status, body.length + 100L);
        exchange.getResponseBody().write(body, 0, body.length / 2);
        exchange.close();

    }

    /** 두 구분 중 하나만 참이고, 요청·응답의 글은 예외 메시지와 원인 어디에도 없다 */
    private void assertFailure(boolean retryable) {

        assertThatThrownBy(() -> adapter.nextQuestion(request()))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isEqualTo(retryable);
                    assertThat(failure.isInvalidResponse()).isEqualTo(!retryable);
                    for (Throwable chain = failure; chain != null; chain = chain.getCause()) {
                        assertThat(String.valueOf(chain.getMessage())).doesNotContain(REQUEST_TEXT, RESPONSE_TEXT);
                    }
                });

    }
}
