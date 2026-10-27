package com.gitory.backend.consent.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubDisconnectService;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.port.DiffEvidence;
import com.gitory.backend.consent.port.DiffEvidencePartialReason;
import com.gitory.backend.consent.port.DiffEvidenceResult;
import com.gitory.backend.consent.port.DiffEvidenceSource;
import com.gitory.backend.consent.port.DiffEvidenceTarget;
import com.gitory.backend.consent.port.GithubCollectionRepo;
import com.gitory.backend.consent.port.GithubCollectionTarget;
import com.gitory.backend.support.AiTestProperties;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 실제 HTTP 를 거쳐 diff 근거 요청의 모양·토큰 위치와 응답 확인을 검사한다 */
class DiffEvidenceAccessTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String PATH = "/internal/analysis/diff-evidence";
    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";
    private static final long USER_ID = 7;
    private static final long USER_REPOSITORY_ID = 123;
    private static final String SHA = "abc1234567890";
    private static final String RESPONSE_TEXT = "response-only-text";

    private final UserRepository users = mock(UserRepository.class);
    private final GithubConnectionRepository connections = mock(GithubConnectionRepository.class);
    private final TokenCipher cipher = new TokenCipher("diff-evidence-key", "0123456789abcdef");
    private final GithubDisconnectService disconnects = mock(GithubDisconnectService.class);
    private final AtomicInteger calls = new AtomicInteger();

    private HttpServer server;
    private GithubCollectionAccess access;
    private volatile String responseBody;
    private volatile int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestToken;
    private volatile String requestBody;

    @BeforeEach
    void setUp() throws Exception {

        responseBody = success(resultData());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
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
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        when(users.findById(USER_ID)).thenReturn(Optional.of(User.register(42L, "grow22")));
        connectionExpiringAt(null);
        access = accessOf(properties().build());

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("diff 근거 경로로 후보 커밋을 보내고 토큰은 헤더에만 실으며, 커밋별 근거를 그대로 돌려준다")
    void sendsTokenOnlyInHeaderAndReturnsEvidence() {

        DiffEvidenceResult result = access.diffEvidence(USER_ID, target(17));

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo(PATH);
        assertThat(requestToken).isEqualTo(TOKEN);
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {
                  "user_repository_id": 123,
                  "repository": {"github_repo_id": 456789, "owner_login": "grow22", "name": "gitory",
                                 "default_branch": "develop"},
                  "commit_shas": ["abc1234567890", "def5678"],
                  "github_pr_number": 17,
                  "title": "세션 저장소를 DB 로 옮김"
                }
                """));
        assertThat(result).isEqualTo(new DiffEvidenceResult(List.of(new DiffEvidence(SHA, "feat: 세션 DB 저장",
                "grow22", "+71/-0", "세션을 JDBC 저장소로 옮겼다", DiffEvidenceSource.DIFF,
                List.of("Spring Session JDBC"), "https://github.com/grow22/gitory/commit/abc1234567890",
                List.of("src/main/java/SessionConfig.java"))), false, null, List.of()));

    }

    @ParameterizedTest
    @MethodSource("withoutChangedPaths")
    @DisplayName("바뀐 파일 경로를 보내기 전의 AI 가 changed_paths 칸을 빼거나 null 로 보내면 빈 목록으로 받는다")
    void readsMissingChangedPathsAsEmpty(ObjectNode data) {

        responseBody = success(data);

        assertThat(access.diffEvidence(USER_ID, target(17)).evidence().getFirst().changedPaths()).isEmpty();

    }

    @Test
    @DisplayName("PR 이 아닌 후보는 github_pr_number 를 보내지 않는다")
    void omitsPrNumberForNonPrCandidate() {

        access.diffEvidence(USER_ID, target(null));

        assertThat(MAPPER.readTree(requestBody).has("github_pr_number")).isFalse();

    }

    @Test
    @DisplayName("일부 커밋을 읽지 못한 부분 결과는 이유와 빠진 커밋을 그대로 돌려준다")
    void keepsPartialResult() {

        responseBody = success(result(data -> {
            data.put("partial", true).put("partial_reason", "COMMIT_UNAVAILABLE");
            data.putArray("missing_commit_shas").add("def5678");
        }));

        DiffEvidenceResult result = access.diffEvidence(USER_ID, target(17));

        assertThat(result.partial()).isTrue();
        assertThat(result.partialReason()).isEqualTo(DiffEvidencePartialReason.COMMIT_UNAVAILABLE);
        assertThat(result.missingCommitShas()).containsExactly("def5678");
        assertThat(result.evidence()).hasSize(1);

    }

    @Test
    @DisplayName("모델 요약 없이 커밋 메시지로 대신한 근거와 작성자·주소가 없는 근거도 받는다")
    void acceptsCommitMessageFallback() {

        responseBody = success(result(data -> ((ObjectNode) data.path("evidence").path(0))
                .put("summary_source", "COMMIT_MESSAGE").putNull("author_login").putNull("url")
                .putArray("technical_points")));

        DiffEvidence evidence = access.diffEvidence(USER_ID, target(17)).evidence().getFirst();

        assertThat(evidence.summarySource()).isEqualTo(DiffEvidenceSource.COMMIT_MESSAGE);
        assertThat(evidence.authorLogin()).isNull();
        assertThat(evidence.url()).isNull();
        assertThat(evidence.technicalPoints()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "REVOKED", "EXPIRED"})
    @DisplayName("쓸 토큰이 없으면 AI 를 부르지 않고, 다시 연결해야 한다는 GITHUB_UNAUTHORIZED 로 끝난다")
    void rejectsWithoutUsableToken(String state) {

        switch (state) {
            case "NONE" -> when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.empty());
            case "REVOKED" -> connectionExpiringAt(null).revoke();
            default -> connectionExpiringAt(Instant.now().minusSeconds(1));
        }

        assertError("GITHUB_UNAUTHORIZED", false);
        assertThat(calls).hasValue(0);

    }

    @Test
    @DisplayName("탈퇴를 요청한 사용자면 AI 를 부르지 않고 연결 없음으로 끝난다")
    void rejectsLeavingUser() {

        User leaving = mock(User.class);
        when(leaving.isDeletionRequested()).thenReturn(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(leaving));

        assertError("GITHUB_CONNECTION_UNAVAILABLE", false);
        assertThat(calls).hasValue(0);

    }

    @ParameterizedTest
    @ValueSource(strings = {"DIFF_EVIDENCE", "COLLECT"})
    @DisplayName("AI 가 GitHub 이 토큰을 거절했다고 알리면 그 토큰의 암호문으로 연결 해제를 요청하고 실패는 그대로 넘긴다")
    void rejectedTokenRevokesThatConnection(String call) {

        GithubConnection connection = connectionExpiringAt(null);
        responseStatus = 401;
        responseBody = failure("GITHUB_UNAUTHORIZED", false);

        assertThatThrownBy(() -> call(call)).isInstanceOfSatisfying(AiClientException.class, error -> {
            assertThat(error.errorCode()).isEqualTo("GITHUB_UNAUTHORIZED");
            assertThat(error.retryable()).isFalse();
        });
        verify(disconnects).revokeRejected(USER_ID, connection.getTokenEnc());

    }

    @ParameterizedTest
    @ValueSource(strings = {"DIFF_EVIDENCE", "COLLECT"})
    @DisplayName("GitHub 일시 장애로 실패하면 연결은 해제하지 않는다")
    void temporaryGithubFailureKeepsConnection(String call) {

        responseStatus = 502;
        responseBody = failure("GITHUB_API_ERROR", true);

        assertThatThrownBy(() -> call(call)).isInstanceOf(AiClientException.class);
        verify(disconnects, never()).revokeRejected(anyLong(), any());

    }

    @ParameterizedTest
    @MethodSource("malformedResults")
    @DisplayName("계약과 다른 응답은 이상한 응답으로 끝난다")
    void rejectsMalformedResult(ObjectNode data) {

        responseBody = success(data);

        assertError("AI_INVALID_RESPONSE", false);

    }

    @Test
    @DisplayName("응답의 글이 든 예외는 원인으로 넘기지 않아, 모르는 값이 예외 메시지로 새지 않는다")
    void doesNotLeakResponseText() {

        responseBody = success(result(data -> ((ObjectNode) data.path("evidence").path(0))
                .put("summary_source", RESPONSE_TEXT)));

        assertThatThrownBy(() -> access.diffEvidence(USER_ID, target(17)))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.getMessage()).doesNotContain(RESPONSE_TEXT);
                    assertThat(error.getCause()).isNull();
                });

    }

    @ParameterizedTest
    @CsvSource({"502, GITHUB_API_ERROR, true", "502, GITHUB_API_ERROR, false", "503, LLM_UNAVAILABLE, true",
            "404, RESOURCE_NOT_FOUND, false"})
    @DisplayName("AI 가 알린 실패는 코드와 다시 시도 여부를 그대로 넘겨, 부르는 쪽이 Job 결과를 정하게 한다")
    void passesAiFailureThrough(int status, String code, boolean retryable) {

        responseStatus = status;
        responseBody = failure(code, retryable);

        assertError(code, retryable);

    }

    @Test
    @DisplayName("응답이 diff 근거 대기 시간을 넘기면 다시 시도할 수 있는 실패로 끝난다")
    void timesOutByDiffEvidenceReadTimeout() {

        responseDelayMs = 600;
        access = accessOf(properties().diffEvidenceReadTimeout(Duration.ofMillis(100)).build());

        assertError("AI_UNAVAILABLE", true);

    }

    @Test
    @DisplayName("diff 근거 대기 시간이 기본값이면 다른 대기 시간이 짧아도 응답을 끝까지 기다려 받는다")
    void waitsByDiffEvidenceReadTimeoutOnly() {

        responseDelayMs = 600;
        access = accessOf(properties().readTimeout(Duration.ofMillis(100)).collectReadTimeout(Duration.ofMillis(100))
                .starReadTimeout(Duration.ofMillis(100)).build());

        assertThat(access.diffEvidence(USER_ID, target(17)).evidence()).hasSize(1);

    }

    static Stream<Arguments> malformedResults() {

        return Stream.of(
                argumentSet("user_repository_id 가 요청과 다르다", result(data -> data.put("user_repository_id", 124))),
                argumentSet("user_repository_id 가 없다", result(data -> data.remove("user_repository_id"))),
                argumentSet("user_repository_id 가 글이다", result(data -> data.put("user_repository_id", "123"))),
                argumentSet("evidence 가 배열이 아니다", result(data -> data.put("evidence", "없음"))),
                argumentSet("sha 가 7자보다 짧다", evidence(item -> item.put("sha", "abc123"))),
                argumentSet("sha 가 40자보다 길다", evidence(item -> item.put("sha", "a".repeat(41)))),
                argumentSet("message 가 없다", evidence(item -> item.remove("message"))),
                argumentSet("churn 이 없다", evidence(item -> item.remove("churn"))),
                argumentSet("summary 가 null 이다", evidence(item -> item.putNull("summary"))),
                argumentSet("summary_source 가 없다", evidence(item -> item.remove("summary_source"))),
                argumentSet("summary_source 가 모르는 값이다", evidence(item -> item.put("summary_source", "LLM"))),
                argumentSet("technical_points 에 글이 아닌 값이 있다",
                        evidence(item -> item.putArray("technical_points").add(1))),
                argumentSet("changed_paths 에 글이 아닌 값이 있다",
                        evidence(item -> item.putArray("changed_paths").add(1))),
                argumentSet("author_login 이 글이 아니다", evidence(item -> item.put("author_login", 42))),
                argumentSet("partial 이 없다", result(data -> data.remove("partial"))),
                argumentSet("partial 이 글이다", result(data -> data.put("partial", "false"))),
                argumentSet("partial_reason 이 모르는 값이다", result(data -> {
                    data.put("partial", true).put("partial_reason", "UNKNOWN");
                    data.putArray("missing_commit_shas").add("def5678");
                })),
                argumentSet("partial 인데 이유가 없다", result(data -> {
                    data.put("partial", true);
                    data.putArray("missing_commit_shas").add("def5678");
                })),
                argumentSet("partial 인데 빠진 커밋이 없다", result(data -> data.put("partial", true)
                        .put("partial_reason", "CAP_EXCEEDED"))),
                argumentSet("partial 이 아닌데 이유가 있다", result(data -> data.put("partial_reason", "CAP_EXCEEDED"))),
                argumentSet("partial 이 아닌데 빠진 커밋이 있다",
                        result(data -> data.putArray("missing_commit_shas").add("def5678"))),
                argumentSet("missing_commit_shas 가 없다", result(data -> data.remove("missing_commit_shas"))));

    }

    static Stream<Arguments> withoutChangedPaths() {

        return Stream.of(
                argumentSet("changed_paths 가 없다", evidence(item -> item.remove("changed_paths"))),
                argumentSet("changed_paths 가 null 이다", evidence(item -> item.putNull("changed_paths"))));

    }

    private static ObjectNode resultData() {

        ObjectNode data = MAPPER.createObjectNode().put("user_repository_id", USER_REPOSITORY_ID)
                .put("github_pr_number", 17);
        ObjectNode item = data.putArray("evidence").addObject().put("sha", SHA).put("message", "feat: 세션 DB 저장")
                .put("author_login", "grow22").put("churn", "+71/-0").put("summary", "세션을 JDBC 저장소로 옮겼다")
                .put("summary_source", "DIFF").put("url", "https://github.com/grow22/gitory/commit/abc1234567890");
        item.putArray("technical_points").add("Spring Session JDBC");
        item.putArray("changed_paths").add("src/main/java/SessionConfig.java");
        data.put("partial", false).putNull("partial_reason").putArray("missing_commit_shas");
        return data;

    }

    private static ObjectNode result(Consumer<ObjectNode> edit) {

        ObjectNode data = resultData();
        edit.accept(data);
        return data;

    }

    private static ObjectNode evidence(Consumer<ObjectNode> edit) {

        return result(data -> edit.accept((ObjectNode) data.path("evidence").path(0)));

    }

    private static String success(ObjectNode data) {

        ObjectNode envelope = MAPPER.createObjectNode().put("success", true);
        envelope.set("data", data);
        envelope.set("meta", MAPPER.createObjectNode().putNull("model").put("tool_calls_made", 2)
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

    private static DiffEvidenceTarget target(Integer prNumber) {

        return new DiffEvidenceTarget(USER_REPOSITORY_ID, new GithubCollectionRepo(456789L, "grow22", "gitory", "develop"),
                List.of(SHA, "def5678"), prNumber, "세션 저장소를 DB 로 옮김");

    }

    private GithubConnection connectionExpiringAt(Instant tokenExpiresAt) {

        GithubConnection connection = GithubConnection.grant(USER_ID, new String[]{"read:user"}, cipher.encrypt(TOKEN),
                tokenExpiresAt);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        return connection;

    }

    private AiTestProperties properties() {

        return AiTestProperties.defaults().baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

    }

    private Object call(String call) {

        GithubCollectionRepo repository = new GithubCollectionRepo(456789L, "grow22", "gitory", "develop");
        return call.equals("COLLECT")
                ? access.collect(USER_ID, new GithubCollectionTarget(USER_REPOSITORY_ID, repository, List.of()), List.of())
                : access.diffEvidence(USER_ID, target(17));

    }

    private GithubCollectionAccess accessOf(AiClientProperties properties) {

        return new GithubCollectionAccess(users, connections, cipher, new AiHttpClient(properties, MAPPER), null, null,
                Clock.systemUTC(), disconnects, null, properties);

    }

    /** 실패 코드와 다시 시도 여부를 보고, 예외 메시지에 토큰이 없는지도 본다 */
    private void assertError(String code, boolean retryable) {

        assertThatThrownBy(() -> access.diffEvidence(USER_ID, target(17)))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(code);
                    assertThat(error.retryable()).isEqualTo(retryable);
                    assertThat(error.getMessage()).doesNotContain(TOKEN);
                });

    }
}
