package com.gitory.backend.ingest.infra;

import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.infra.GithubCollectionAccess;
import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import com.gitory.backend.consent.port.GithubCollectionRepo;
import com.gitory.backend.consent.port.GithubCollectionTarget;
import com.gitory.backend.ingest.domain.ExclusionReason;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.port.IngestRequest;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 실제 HTTP를 거쳐 공유 계약의 Spring→AI 및 AI→Spring 경계를 검사한다. */
class AiRepositoryActivityAdapterTest {

    private static final String TOKEN = "contract-only-token";
    private static final long USER_ID = 7;
    private static final long USER_REPOSITORY_ID = 123;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final UserRepository users = mock(UserRepository.class);
    private final GithubConnectionRepository connections = mock(GithubConnectionRepository.class);
    private final CollectionTargetLookup targets = mock(CollectionTargetLookup.class);
    private final TokenCipher cipher = new TokenCipher("contract-test-key", "0123456789abcdef");
    private final AtomicInteger calls = new AtomicInteger();

    private HttpServer server;
    private AiRepositoryActivityAdapter adapter;
    private AiHttpClient http;
    private ObjectNode data;
    private String responseBody;
    private int responseStatus = 200;
    private volatile long responseDelayMs;
    private volatile boolean responseHeadersFirst;
    private volatile String requestBody;
    private volatile String requestToken;
    private volatile String requestMethod;
    private volatile String requestPath;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode fixture = mapper.readTree(Path.of("../contracts/ingest/repository-activity.json"));
        data = (ObjectNode) fixture.path("ai_to_spring").path("data").deepCopy();
        responseBody = success(data);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestToken = exchange.getRequestHeaders().getFirst("X-GitHub-Token");
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (responseHeadersFirst) {
                exchange.sendResponseHeaders(responseStatus, body.length);
            }
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            if (!responseHeadersFirst) {
                exchange.sendResponseHeaders(responseStatus, body.length);
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        http = new AiHttpClient(new AiClientProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(2)), mapper);
        when(users.findById(USER_ID)).thenReturn(Optional.of(User.register(42L, "grow22")));
        activeConnection(null);
        when(targets.find(eq(USER_REPOSITORY_ID), anyList())).thenAnswer(invocation ->
                new CollectionTargetLookup.Target(USER_ID,
                        new GithubCollectionTarget(USER_REPOSITORY_ID,
                                new GithubCollectionRepo(456789L, "grow22", "gitory", "develop"),
                                invocation.getArgument(1))));
        adapter = new AiRepositoryActivityAdapter(targets,
                new GithubCollectionAccess(users, connections, cipher, http, null, null));
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    @DisplayName("DB 저장소 정보와 서버 사용자로 요청을 만들고, 토큰은 헤더에만 전달한다")
    void collectsUsingSharedContractOverHttp() {
        var activity = adapter.collect(request(List.of(" feature/session-index ", "feature/session-index")));

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestPath).isEqualTo("/internal/collect");
        assertThat(requestToken).isEqualTo(TOKEN);
        assertThat(requestBody).doesNotContain(TOKEN).doesNotContain("token");
        JsonNode sent = mapper.readTree(requestBody);
        assertThat(sent.path("user_repository_id").longValue()).isEqualTo(USER_REPOSITORY_ID);
        assertThat(sent.path("repository").path("github_repo_id").longValue()).isEqualTo(456789L);
        assertThat(sent.path("repository").path("default_branch").asString()).isEqualTo("develop");
        assertThat(sent.path("actor").path("github_login").asString()).isEqualTo("grow22");
        assertThat(sent.path("actor").path("known_emails").isEmpty()).isTrue();
        assertThat(sent.path("branches").size()).isEqualTo(1);
        assertThat(sent.path("branches").path(0).asString()).isEqualTo("feature/session-index");
        assertThat(sent.has("since")).isFalse();
        assertThat(activity.headSha()).isEqualTo("abc1234567890");
        assertThat(activity.commits()).hasSize(1);
        assertThat(activity.commits().getFirst().sha()).isEqualTo("abc1234567890");
        assertThat(activity.commits().getFirst().authoredAt()).isEqualTo(Instant.parse("2026-09-20T09:00:00Z"));
        assertThat(activity.pullRequests().getFirst().number()).isEqualTo(17);
        assertThat(activity.issues().getFirst().issueNumber()).isEqualTo(42);
        assertThat(activity.partialReason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GITHUB_RATE_LIMITED", "CAP_EXCEEDED"})
    @DisplayName("부분 수집도 활동과 정확한 사유를 보존한다")
    void keepsPartialCollection(String reason) {
        data.put("partial", true).put("partial_reason", reason);
        ((ObjectNode) data.path("commits").path(0)).put("is_excluded", true).put("exclusion_reason", "NOT_OWN");
        responseBody = success(data);
        var activity = adapter.collect(request(List.of()));
        assertThat(activity.partialReason()).isEqualTo(PartialReason.valueOf(reason));
        assertThat(activity.commits().getFirst().exclusionReason()).isEqualTo(ExclusionReason.NOT_OWN);
    }

    @Test
    @DisplayName("user_repository_id 불일치 응답은 저장으로 넘기지 않는다")
    void rejectsWrongConnectionIdentity() {
        data.put("user_repository_id", 124);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("다른 GitHub 저장소에서 온 커밋은 저장으로 넘기지 않는다")
    void rejectsWrongGithubRepositoryIdentity() {
        ((ObjectNode) data.path("commits").path(0)).put("repository_id", 999);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("부분 여부와 사유가 어긋난 응답은 거절한다")
    void rejectsInconsistentPartialFlags() {
        data.put("partial", true);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("음수 숫자·범위 넘는 parent_count 응답은 거절한다")
    void rejectsOverflowingParentCount() {
        ((ObjectNode) data.path("commits").path(0)).put("parent_count", 40000);
        responseBody = success(data);
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("HTTP 오류 봉투의 code·retryable만 보존하고 원문 오류는 버린다")
    void sanitizesHttpEnvelopeFailure() {
        responseStatus = 502;
        responseBody = """
                {"success":false,"data":null,"meta":{},
                 "error":{"code":"GITHUB_API_ERROR","message":"contract-only-token","retryable":true}}
                """;
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("GITHUB_API_ERROR");
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.httpStatus()).isEqualTo(502);
                    assertThat(error.getMessage()).doesNotContain(TOKEN);
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("HTTP 200이어도 실패 봉투를 성공 결과로 처리하지 않는다")
    void rejectsLogicalFailureOnSuccessfulHttpStatus() {
        responseBody = """
                {"success":false,"data":null,"meta":{},
                 "error":{"code":"RESOURCE_NOT_FOUND","message":"missing","retryable":false}}
                """;
        assertError("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("알 수 없는 에러 코드 원문은 예외 메시지로 새지 않는다")
    void rejectsUntrustedErrorCode() {
        responseBody = """
                {"success":false,"error":{"code":"contract-only-token","retryable":true}}
                """;
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("FastAPI validation 오류도 HTTP 오류로 분류하고 detail은 보관하지 않는다")
    void sanitizesNonEnvelopeHttpFailure() {
        responseStatus = 422;
        responseBody = "{\"detail\":\"contract-only-token\"}";
        assertError("AI_HTTP_ERROR");
    }

    @Test
    @DisplayName("JSON이 아닌 성공 응답은 거절한다")
    void rejectsMalformedJson() {
        responseBody = "<html>contract-only-token</html>";
        assertError("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("철회된 연결로는 AI를 호출하지 않는다")
    void rejectsRevokedConnection() {
        GithubConnection connection = activeConnection(null);
        connection.revoke();
        assertError("GITHUB_CONNECTION_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("만료된 연결로는 AI를 호출하지 않는다")
    void rejectsExpiredConnection() {
        activeConnection(Instant.now().minusSeconds(1));
        assertError("GITHUB_CONNECTION_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 127, 233})
    @DisplayName("NUL·DEL·비ASCII 토큰은 헤더 생성 전에 안전하게 거절한다")
    void rejectsUnsafeTokenWithoutLeakingHeaderValue(int character) {
        String unsafeToken = TOKEN + (char) character;
        GithubConnection connection = GithubConnection.grant(USER_ID,
                new String[]{"read:user", "public_repo"}, cipher.encrypt(unsafeToken), null);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        assertError("GITHUB_CONNECTION_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("증분 수집 기준 since를 AI 요청에 그대로 전달한다")
    void forwardsIncrementalCollectionSince() throws Exception {
        Instant since = Instant.parse("2026-09-20T00:00:00Z");

        adapter.collect(new IngestRequest(USER_REPOSITORY_ID, List.of(), since));

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(sent.path("since").asString()).isEqualTo("2026-09-20T00:00:00Z");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("연결 실패는 재시도 가능한 안전한 전송 오류다")
    void classifiesTransportFailure() {
        server.stop(0);
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.getCause()).isNull();
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("수집 read timeout은 원문 없이 재시도 가능한 전송 오류로 반환한다")
    void appliesCollectionReadTimeout(boolean headersFirst) {
        responseDelayMs = 500;
        responseHeadersFirst = headersFirst;
        AiHttpClient shortTimeout = new AiHttpClient(new AiClientProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofMillis(50)), mapper);
        adapter = new AiRepositoryActivityAdapter(targets,
                new GithubCollectionAccess(users, connections, cipher, shortTimeout, null, null));
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("health는 AI 봉투 검증 없이 원본 JSON을 조회한다")
    void readsRawHealthJsonWithoutToken() {
        responseBody = "{\"status\":\"ok\"}";
        assertThat(http.get("/health").path("status").asString()).isEqualTo("ok");
        assertThat(requestToken).isNull();
        assertThat(requestMethod).isEqualTo("GET");
    }

    private GithubConnection activeConnection(Instant expiry) {
        GithubConnection connection = GithubConnection.grant(USER_ID, new String[]{"read:user", "public_repo"},
                cipher.encrypt(TOKEN), expiry);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        return connection;
    }

    private IngestRequest request(List<String> branches) {
        return new IngestRequest(USER_REPOSITORY_ID, branches, null);
    }

    private String success(ObjectNode result) {
        ObjectNode envelope = mapper.createObjectNode().put("success", true);
        envelope.set("data", result);
        envelope.set("error", mapper.nullNode());
        envelope.set("meta", mapper.createObjectNode().put("model", "github-collector")
                .put("tool_calls_made", 5).put("processing_ms", 10));
        return mapper.writeValueAsString(envelope);
    }

    private void assertError(String code) {
        assertThatThrownBy(() -> adapter.collect(request(List.of())))
                .isInstanceOfSatisfying(AiClientException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(code);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain(TOKEN);
                });
    }
}
