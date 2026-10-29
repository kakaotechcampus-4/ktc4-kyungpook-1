package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.ExperienceCandidate;
import com.gitory.backend.agent.port.ExperienceGroupingRequest;
import com.gitory.backend.agent.port.ExperienceGroupingResponse;
import com.gitory.backend.agent.port.ExperienceSourceType;
import com.gitory.backend.agent.port.GroupingCommit;
import com.gitory.backend.agent.port.GroupingFile;
import com.gitory.backend.agent.port.GroupingFileStatus;
import com.gitory.backend.agent.port.GroupingIssue;
import com.gitory.backend.agent.port.GroupingPullRequest;
import com.gitory.backend.agent.port.GroupingVerdict;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.gitory.backend.common.infra.ai.AiProgress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공유 계약 예시와 가짜 AI 응답으로 경험 그룹화의 요청·응답 경계를 검사한다 */
class AiExperienceGroupingAdapterTest {

    private static final String C1 = "3f2a9c1e7b4d58f60a1c2e3d4b5f6a7980b1c2d3";
    private static final String C2 = "8c41d07e2f9b3a6c5d1e0f2a4b6c8d0e1f3a5b7c";
    private static final String C3 = "d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4";
    private static final String C4 = "0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c";
    private static final String EMOJI = "\uD83D\uDE00";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private HttpServer server;
    private JsonNode contractRequest;
    private ObjectNode data;
    private volatile int responseStatus = 200;
    private volatile String responseContentType = "application/json";
    private volatile String responseBody;
    private volatile long responseDelayMs;
    private volatile boolean cutBody;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestToken;
    private volatile String requestBody;

    @BeforeEach
    void setUp() throws Exception {

        JsonNode contract = mapper.readTree(Path.of("../contracts/analysis/experience-groups.json"));
        contractRequest = contract.path("spring_to_ai").path("request");
        data = (ObjectNode) contract.path("ai_to_spring").path("data").deepCopy();
        responseBody = success(data);
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
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("경험 그룹화 요청은 GitHub 토큰 없이 /internal/analysis/groups 로 보내고, 본문은 공유 계약 예시와 칸 이름·값이 모두 같다")
    void sendsContractRequestWithoutToken() {

        adapter().group(request());

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo("/internal/analysis/groups");
        assertThat(requestToken).isNull();
        assertThat(mapper.readTree(requestBody)).isEqualTo(contractRequest);

    }

    @Test
    @DisplayName("진행을 받으면 /internal/analysis/groups/stream 로 보내 진행을 넘기고, 결과는 스트리밍 없는 그룹화와 같게 읽는다")
    void streamsProgressAndReadsSameResult() {

        List<AiProgress> received = new ArrayList<>();
        responseContentType = "application/x-ndjson";
        responseBody = "{\"type\":\"progress\",\"step\":\"COMPRESS\",\"done\":1,\"total\":2}\n"
                + "{\"type\":\"result\",\"status\":200,\"body\":" + success(data) + "}\n";

        ExperienceGroupingResponse streamed = adapter().group(request(), received::add);

        assertThat(requestPath).isEqualTo("/internal/analysis/groups/stream");
        assertThat(received).containsExactly(new AiProgress("COMPRESS", 1, 2));
        responseContentType = "application/json";
        responseBody = success(data);
        assertThat(streamed).isEqualTo(adapter().group(request()));

    }

    @Test
    @DisplayName("다른 AI 호출의 대기 시간이 짧아도 경험 그룹화 전용 대기 시간까지 기다린다")
    void waitsForGroupsReadTimeout() {

        responseDelayMs = 300;
        AiExperienceGroupingAdapter adapter = adapter(AiTestProperties.defaults().baseUrl(baseUrl())
                .readTimeout(Duration.ofMillis(50)).collectReadTimeout(Duration.ofMillis(50))
                .interviewReadTimeout(Duration.ofMillis(50)).groupsReadTimeout(Duration.ofSeconds(3)).build());

        assertThat(adapter.group(request()).candidates()).hasSize(2);

    }

    @Test
    @DisplayName("경험 그룹화 전용 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다")
    void groupsReadTimeoutIsRetryable() {

        responseDelayMs = 300;
        AiExperienceGroupingAdapter adapter = adapter(AiTestProperties.defaults().baseUrl(baseUrl())
                .readTimeout(Duration.ofSeconds(3)).collectReadTimeout(Duration.ofSeconds(3))
                .interviewReadTimeout(Duration.ofSeconds(3)).groupsReadTimeout(Duration.ofMillis(50)).build());

        assertFailure(adapter, true, false, "AI_UNAVAILABLE");

    }

    @Test
    @DisplayName("AI 에 연결하지 못하면 다시 시도할 수 있는 실패로 끝난다")
    void connectionFailureIsRetryable() {

        AiExperienceGroupingAdapter adapter = adapter();
        server.stop(0);

        assertFailure(adapter, true, false, "AI_UNAVAILABLE");

    }

    @Test
    @DisplayName("공유 계약 예시 응답의 판정·후보의 모든 칸·AI 가 뺀 커밋을 그대로 옮긴다")
    void mapsContractResponse() {

        ExperienceGroupingResponse response = adapter().group(request());

        assertThat(response).isEqualTo(new ExperienceGroupingResponse(GroupingVerdict.OK, List.of(
                new ExperienceCandidate("pr:17", ExperienceSourceType.PR, "#17", 17, List.of(17), List.of(42),
                        "세션 조회 인덱스 추가와 쿼리 정리",
                        "세션 테이블에 인덱스를 만들고 조회 쿼리를 리포지터리로 옮긴 커밋 2개가 있습니다.", 0.7037, false,
                        List.of(C1, C2)),
                new ExperienceCandidate("cluster:2026-09-23:d5e6f7a8", ExperienceSourceType.COMMIT_CLUSTER,
                        "2026-09-23", null, List.of(), List.of(), "로그인 뒤 원래 페이지로 돌아가기",
                        "로그인 콜백에서 돌아갈 경로를 고친 커밋 1개가 있습니다.", 0.3799, true, List.of(C3))),
                List.of(C4)));

    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "PARTIAL"})
    @DisplayName("후보가 0개인 EMPTY·PARTIAL 응답도 빈 후보 목록과 판정·AI 가 뺀 커밋을 그대로 옮긴다")
    void acceptsResponseWithoutCandidates(String verdict) {

        data.put("verdict", verdict);
        data.putArray("candidates");
        data.putArray("excluded_commit_shas").add(C1).add(C2).add(C3).add(C4);
        responseBody = success(data);

        assertThat(adapter().group(request())).isEqualTo(
                new ExperienceGroupingResponse(GroupingVerdict.valueOf(verdict), List.of(), List.of(C1, C2, C3, C4)));

    }

    @Test
    @DisplayName("부분 수집이면 후보가 있어도 PARTIAL 판정을 그대로 옮긴다")
    void keepsPartialVerdictWithCandidates() {

        data.put("verdict", "PARTIAL");
        responseBody = success(data);

        ExperienceGroupingResponse response = adapter().group(request());

        assertThat(response.verdict()).isEqualTo(GroupingVerdict.PARTIAL);
        assertThat(response.candidates()).hasSize(2);

    }

    @Test
    @DisplayName("계약에서 비어도 되는 ref 는 null 로 받는다")
    void acceptsNullSourceRef() {

        candidate(1).putNull("source_ref");
        responseBody = success(data);

        assertThat(adapter().group(request()).candidates().get(1).sourceRef()).isNull();

    }

    @Test
    @DisplayName("AI 가 응답에 모르는 칸을 더해도 아는 칸만 읽는다")
    void ignoresUnknownResponseFields() {

        data.put("model", "grouping-v2");
        candidate(0).putObject("meta").put("commit_count", 2);
        responseBody = success(data);

        assertThat(adapter().group(request()).candidates().get(0).commitShas()).containsExactly(C1, C2);

    }

    @Test
    @DisplayName("제목 200자·이유 300자는 이모지도 한 글자로 세어 받는다")
    void acceptsTextAtCodePointLimit() {

        candidate(0).put("title", EMOJI.repeat(200)).put("reason", EMOJI.repeat(300));
        responseBody = success(data);

        ExperienceCandidate candidate = adapter().group(request()).candidates().get(0);

        assertThat(candidate.title()).isEqualTo(EMOJI.repeat(200));
        assertThat(candidate.reason()).isEqualTo(EMOJI.repeat(300));

    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0})
    @DisplayName("점수는 0 과 1 도 받는다")
    void acceptsScoreAtBounds(double score) {

        candidate(0).put("score", score);
        responseBody = success(data);

        assertThat(adapter().group(request()).candidates().get(0).score()).isEqualTo(score);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenShapes")
    @DisplayName("칸이 없거나 타입·범위·값이 계약과 다른 응답은 다시 시도해도 같은 이상 응답으로 끝낸다")
    void rejectsBrokenShape(Consumer<ObjectNode> change) {

        change.accept(data);
        responseBody = success(data);

        assertFailure(adapter(), false, true, "AI_INVALID_RESPONSE");

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenCommitLinks")
    @DisplayName("후보 커밋이 보낸 커밋이 아니거나, 한 커밋이 두 번 들어가거나, 커밋 없는 후보가 오면 이상 응답으로 끝낸다")
    void rejectsBrokenCommitLinks(Consumer<ObjectNode> change) {

        change.accept(data);
        responseBody = success(data);

        assertFailure(adapter(), false, true, "AI_INVALID_RESPONSE");

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenPullRequestFields")
    @DisplayName("대표 PR 은 PR 후보에만 있고 그 후보의 PR 목록 안에 있어야 하며, 아니면 이상 응답으로 끝낸다")
    void rejectsBrokenPullRequestFields(Consumer<ObjectNode> change) {

        change.accept(data);
        responseBody = success(data);

        assertFailure(adapter(), false, true, "AI_INVALID_RESPONSE");

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureResponses")
    @DisplayName("429·502·503·504 와 다시 시도하라는 봉투는 다시 시도하고, 400·500·이상 응답과 다시 해도 소용없다는 봉투는 이상한 응답으로 끝낸다")
    void mapsFailureResponses(int status, String contentType, String body, boolean retryable, boolean invalid,
                              String code) {

        responseStatus = status;
        responseContentType = contentType;
        responseBody = body;

        assertFailure(adapter(), retryable, invalid, code);

    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 500})
    @DisplayName("응답 헤더 뒤에 본문이 끊기면 상태가 무엇이든 다시 시도할 수 있는 실패로 끝난다")
    void cutBodyIsRetryable(int status) {

        responseStatus = status;
        cutBody = true;

        assertFailure(adapter(), true, false, "AI_UNAVAILABLE");

    }

    @Test
    @DisplayName("이상 응답으로 끝낼 때 AI 응답 원문은 예외 메시지와 원인에 남기지 않는다")
    void keepsRawResponseOutOfFailure() {

        data.put("verdict", "LEAKED-VERDICT");
        responseBody = success(data);

        assertThatThrownBy(() -> adapter().group(request()))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        assertThat(cause.getMessage()).doesNotContain("LEAKED");
                    }
                });

    }

    static Stream<Arguments> brokenShapes() {

        return Stream.of(
                broken("판정이 정한 값이 아니다", data -> data.put("verdict", "MAYBE")),
                broken("판정이 글자가 아니다", data -> data.put("verdict", 1)),
                broken("후보 목록이 배열이 아니다", data -> data.put("candidates", "none")),
                broken("후보가 객체가 아니다", data -> ((ArrayNode) data.get("candidates")).set(0, data.stringNode("pr:17"))),
                broken("후보 종류가 PR·COMMIT_CLUSTER 가 아니다", data -> candidateOf(data, 0).put("source_type", "ISSUE")),
                broken("열쇠가 글자가 아니다", data -> candidateOf(data, 0).put("group_key", 17)),
                broken("열쇠가 null 이다", data -> candidateOf(data, 0).putNull("group_key")),
                broken("ref 가 글자가 아니다", data -> candidateOf(data, 0).put("source_ref", 17)),
                broken("대표 PR 이 0 이다", data -> {
                    candidateOf(data, 0).put("pull_request_number", 0);
                    candidateOf(data, 0).putArray("pull_request_numbers").add(0);
                }),
                broken("대표 PR 이 정수가 아니다(17.0)", data -> candidateOf(data, 0).put("pull_request_number", 17.0)),
                broken("대표 PR 이 int 범위를 넘는다", data -> {
                    candidateOf(data, 0).put("pull_request_number", 4_294_967_313L);
                    candidateOf(data, 0).putArray("pull_request_numbers").add(4_294_967_313L);
                }),
                broken("PR 목록이 배열이 아니다", data -> candidateOf(data, 0).put("pull_request_numbers", 17)),
                broken("PR 목록에 0 이하 번호가 있다", data ->
                        candidateOf(data, 0).putArray("pull_request_numbers").add(17).add(-1)),
                broken("이슈 목록이 배열이 아니다", data -> candidateOf(data, 0).put("issue_numbers", 42)),
                broken("이슈 목록에 0 이하 번호가 있다", data -> candidateOf(data, 0).putArray("issue_numbers").add(0)),
                broken("제목이 공백뿐이다", data -> candidateOf(data, 0).put("title", "   ")),
                broken("제목이 200자를 넘는다", data -> candidateOf(data, 0).put("title", EMOJI.repeat(201))),
                broken("제목이 없다", data -> candidateOf(data, 0).remove("title")),
                broken("이유가 공백뿐이다", data -> candidateOf(data, 0).put("reason", "\t")),
                broken("이유가 300자를 넘는다", data -> candidateOf(data, 0).put("reason", EMOJI.repeat(301))),
                broken("이유가 null 이다", data -> candidateOf(data, 0).putNull("reason")),
                broken("점수가 0 보다 작다", data -> candidateOf(data, 0).put("score", -0.0001)),
                broken("점수가 1 보다 크다", data -> candidateOf(data, 0).put("score", 1.0001)),
                broken("점수가 숫자가 아니다", data -> candidateOf(data, 0).put("score", "0.7")),
                broken("카드감 낮음이 참·거짓이 아니다", data -> candidateOf(data, 0).put("low_card_worth", "false")),
                broken("카드감 낮음이 없다", data -> candidateOf(data, 0).remove("low_card_worth")),
                broken("후보 커밋 목록이 배열이 아니다", data -> candidateOf(data, 0).put("commit_shas", C1)),
                broken("AI 가 뺀 커밋 목록이 배열이 아니다", data -> data.put("excluded_commit_shas", C4)),
                broken("AI 가 뺀 커밋에 글자가 아닌 값이 있다", data -> data.putArray("excluded_commit_shas").add(123)));

    }

    static Stream<Arguments> brokenCommitLinks() {

        return Stream.of(
                broken("보내지 않은 커밋이 후보에 있다", data ->
                        candidateOf(data, 0).putArray("commit_shas").add(C1).add("f".repeat(40))),
                broken("한 커밋이 두 후보에 들어 있다", data -> candidateOf(data, 1).putArray("commit_shas").add(C3).add(C1)),
                broken("한 후보에 같은 커밋이 두 번 있다", data ->
                        candidateOf(data, 0).putArray("commit_shas").add(C1).add(C2).add(C1)),
                broken("커밋이 없는 후보가 있다", data -> candidateOf(data, 1).putArray("commit_shas")));

    }

    static Stream<Arguments> brokenPullRequestFields() {

        return Stream.of(
                broken("PR 후보에 대표 PR 이 없다", data -> candidateOf(data, 0).putNull("pull_request_number")),
                broken("커밋 묶음 후보에 대표 PR 이 있다", data -> candidateOf(data, 1).put("pull_request_number", 5)),
                broken("커밋 묶음 후보에 PR 목록이 있다", data -> candidateOf(data, 1).putArray("pull_request_numbers").add(5)),
                broken("대표 PR 이 PR 목록에 없다", data -> candidateOf(data, 0).putArray("pull_request_numbers").add(18)),
                broken("PR 후보의 PR 목록이 비어 있다", data -> candidateOf(data, 0).putArray("pull_request_numbers")));

    }

    static Stream<Arguments> failureResponses() {

        return Stream.of(
                Arguments.of(Named.of("503 LLM 호출 실패", 503), "application/json",
                        failure("LLM_UNAVAILABLE", "그룹화 LLM 호출에 실패했습니다.", true), true, false, "LLM_UNAVAILABLE"),
                Arguments.of(Named.of("503 LLM 설정 없음", 503), "application/json",
                        failure("LLM_UNAVAILABLE", "그룹화 LLM 설정이 없어 분석할 수 없습니다.", false), false, true,
                        "LLM_UNAVAILABLE"),
                Arguments.of(Named.of("200 다시 시도하라는 오류 봉투", 200), "application/json",
                        failure("LLM_UNAVAILABLE", "그룹화 일시 오류", true), true, false, "LLM_UNAVAILABLE"),
                Arguments.of(Named.of("429 LLM 한도 봉투", 429), "application/json",
                        failure("LLM_RATE_LIMIT", "그룹화 한도 초과", true), true, false, "LLM_RATE_LIMIT"),
                Arguments.of(Named.of("502 GitHub 오류 봉투", 502), "application/json",
                        failure("GITHUB_API_ERROR", "그룹화 GitHub 오류", true), true, false, "GITHUB_API_ERROR"),
                Arguments.of(Named.of("429 봉투 없는 글자", 429), "text/plain", "그룹화 일시 오류", true, false,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("502 봉투 없는 글자", 502), "text/plain", "그룹화 일시 오류", true, false,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("504 봉투 없는 글자", 504), "text/plain", "그룹화 일시 오류", true, false,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("503 봉투 없는 글자", 503), "text/plain", "그룹화 일시 오류", true, false,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("500 다시 해도 소용없다는 오류 봉투", 500), "application/json",
                        failure("INTERNAL_ERROR", "그룹화 내부 오류", false), false, true, "INTERNAL_ERROR"),
                Arguments.of(Named.of("500 LLM 호출 실패 봉투", 500), "application/json",
                        failure("LLM_UNAVAILABLE", "그룹화 LLM 호출 오류", true), false, true, "LLM_UNAVAILABLE"),
                Arguments.of(Named.of("404 봉투 없는 글자", 404), "text/plain", "그룹화 대상 없음", false, true,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("422 봉투 없는 글자", 422), "text/plain", "그룹화 요청 오류", false, true,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("400 요청 검증 실패", 400), "application/json",
                        failure("INVALID_PAYLOAD", "요청 스키마가 명세와 일치하지 않습니다.", false), false, true,
                        "INVALID_PAYLOAD"),
                Arguments.of(Named.of("400 다시 시도하라는 오류 봉투", 400), "application/json",
                        failure("INVALID_PAYLOAD", "그룹화 요청 오류", true), false, true, "INVALID_PAYLOAD"),
                Arguments.of(Named.of("500 다시 시도하라는 오류 봉투", 500), "application/json",
                        failure("INTERNAL_ERROR", "그룹화 내부 오류", true), false, true, "INTERNAL_ERROR"),
                Arguments.of(Named.of("404 대상 없음 봉투", 404), "application/json",
                        failure("RESOURCE_NOT_FOUND", "그룹화 대상 없음", false), false, true, "RESOURCE_NOT_FOUND"),
                Arguments.of(Named.of("401 인증 실패 봉투", 401), "application/json",
                        failure("UNAUTHORIZED", "그룹화 인증 실패", false), false, true, "UNAUTHORIZED"),
                Arguments.of(Named.of("400 봉투 없는 글자", 400), "text/plain", "그룹화 요청 오류", false, true,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("500 글자 응답", 500), "text/plain", "Internal Server Error", false, true,
                        "AI_HTTP_ERROR"),
                Arguments.of(Named.of("200 이지만 JSON 이 아님", 200), "text/html", "<html>그룹화</html>", false, true,
                        "AI_INVALID_RESPONSE"));

    }

    private static Arguments broken(String name, Consumer<ObjectNode> change) {

        return Arguments.of(Named.of(name, change));

    }

    private static ObjectNode candidateOf(ObjectNode data, int index) {

        return (ObjectNode) data.get("candidates").get(index);

    }

    private static String failure(String code, String message, boolean retryable) {

        return """
                {"success":false,"data":null,"meta":{"model":null,"tool_calls_made":0,"processing_ms":3},
                 "error":{"code":"%s","message":"%s","retryable":%s}}
                """.formatted(code, message, retryable);

    }

    private ObjectNode candidate(int index) {

        return candidateOf(data, index);

    }

    private void assertFailure(AiExperienceGroupingAdapter adapter, boolean retryable, boolean invalid, String code) {

        assertThatThrownBy(() -> adapter.group(request()))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isEqualTo(retryable);
                    assertThat(failure.isInvalidResponse()).isEqualTo(invalid);
                    assertThat(failure.getMessage()).isEqualTo("AI 경험 그룹화 실패 (" + code + ")");
                });

    }

    private AiExperienceGroupingAdapter adapter() {

        return adapter(AiTestProperties.defaults().baseUrl(baseUrl()).build());

    }

    private AiExperienceGroupingAdapter adapter(AiClientProperties properties) {

        return new AiExperienceGroupingAdapter(new AiHttpClient(properties, mapper), properties);

    }

    /** 약속한 길이보다 적게 쓰고 닫아 응답 헤더 뒤에 본문이 끊기게 한다 */
    private static void writeCutBody(HttpExchange exchange, int status, byte[] body) throws IOException {

        exchange.sendResponseHeaders(status, body.length + 100L);
        exchange.getResponseBody().write(body, 0, body.length / 2);
        exchange.close();

    }

    private URI baseUrl() {

        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());

    }

    private String success(ObjectNode result) {

        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("meta", mapper.createObjectNode().putNull("model").put("tool_calls_made", 0)
                .put("processing_ms", 12));
        envelope.set("error", mapper.nullNode());
        return mapper.writeValueAsString(envelope);

    }

    private static ExperienceGroupingRequest request() {

        return new ExperienceGroupingRequest("123", "grow22", List.of(
                commit(C1, "feat: 세션 테이블에 인덱스 추가 (#42)", "2026-09-20T18:00:00+09:00", 40, 3, List.of(
                        file("backend/src/main/resources/db/migration/V3__session_index.sql",
                                GroupingFileStatus.ADDED, 12, 0),
                        file("backend/src/main/java/com/gitory/session/SessionRepository.java",
                                GroupingFileStatus.MODIFIED, 28, 3)), 17, List.of(42)),
                commit(C2, "refactor: 세션 조회 쿼리를 리포지터리로 옮김", "2026-09-21T10:30:00+09:00", 25, 18, List.of(
                        file("backend/src/main/java/com/gitory/session/SessionRepository.java",
                                GroupingFileStatus.MODIFIED, 20, 2),
                        file("backend/src/main/java/com/gitory/session/SessionService.java",
                                GroupingFileStatus.MODIFIED, 5, 16)), 17, List.of()),
                commit(C3, "fix: 로그인 뒤 원래 페이지로 돌아가게 수정", "2026-09-23T08:15:00+09:00", 9, 2, List.of(
                        file("frontend/src/features/auth/LoginCallback.tsx", GroupingFileStatus.MODIFIED, 9, 2)),
                        null, List.of()),
                commit(C4, "chore: 빌드 결과물 갱신", "2026-09-23T09:00:00+09:00", 1, 1, List.of(
                        file("frontend/dist/assets/index.min.js", GroupingFileStatus.MODIFIED, 1, 1)),
                        null, List.of())),
                List.of(new GroupingPullRequest(17, "세션 조회 성능 개선",
                        "세션 테이블에 인덱스를 추가하고 조회 쿼리를 정리합니다. Resolves #42", List.of(42))),
                List.of(new GroupingIssue(42, "로그인 뒤 첫 화면이 느립니다", "세션 조회에 3초 넘게 걸립니다.")),
                false);

    }

    private static GroupingCommit commit(String sha, String message, String authoredAt, int additions, int deletions,
                                         List<GroupingFile> files, Integer pullRequestNumber,
                                         List<Integer> issueNumbers) {

        return new GroupingCommit(sha, 456789L, message, "grow22", "Grow", null, OffsetDateTime.parse(authoredAt), 1,
                additions, deletions, files.size(), files, pullRequestNumber, issueNumbers, null, null);

    }

    private static GroupingFile file(String path, GroupingFileStatus status, int additions, int deletions) {

        return new GroupingFile(path, status, additions, deletions, null);

    }
}
