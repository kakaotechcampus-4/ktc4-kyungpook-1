package com.gitory.backend.matching.infra;

import com.gitory.backend.common.infra.ai.AiHttpClient;
import com.gitory.backend.consent.domain.GithubConnection;
import com.gitory.backend.consent.domain.GithubDisconnectService;
import com.gitory.backend.consent.domain.User;
import com.gitory.backend.consent.infra.GithubCollectionAccess;
import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.consent.infra.UserRepository;
import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.GapFillAction;
import com.gitory.backend.matching.domain.GapFillCondition;
import com.gitory.backend.matching.domain.GapFillDecision;
import com.gitory.backend.matching.domain.GapFillProposal;
import com.gitory.backend.matching.domain.GapFillRequest;
import com.gitory.backend.matching.domain.GapFillResult;
import com.gitory.backend.matching.domain.GapFillStep;
import com.gitory.backend.matching.domain.GapFillStepKind;
import com.gitory.backend.matching.domain.MatchingAiUnavailableException;
import com.gitory.backend.support.AiTestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 실제 HTTP 를 거쳐 모자란 조건 채우기 요청의 모양·토큰 위치와 진행 줄·마지막 응답 확인을 검사한다 */
class AiGapFillAdapterTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";
    private static final long USER_ID = 7;
    private static final UUID CONDITION_A = UUID.fromString("0b7d3e19-4c2a-4f81-9d6e-a3c5f7b1e201");
    private static final UUID CONDITION_B = UUID.fromString("1c8e4f2a-5d3b-4a92-8e7f-b4d6a8c2f302");
    private static final UUID CARD = UUID.fromString("f2a8c613-7d4b-4e95-8c1f-6b3d9e2a7c11");
    private static final UUID DRAFT = UUID.fromString("a7c4e291-3f6b-4d18-b2e9-5c8a1d7f3e12");
    private static final UUID CANDIDATE = UUID.fromString("5e2f9a7d-3b02-4d90-b6c3-8a1e5c342f7b");
    private static final UUID ANALYZED_REPO = UUID.fromString("c3e9a1f7-2b4d-4e86-9a1c-7f5d3b8e2a41");
    private static final UUID NEW_REPO = UUID.fromString("d4f0b2a8-3c5e-4f97-8b2d-8a6e4c9f3b52");

    private final UserRepository users = mock(UserRepository.class);
    private final GithubConnectionRepository connections = mock(GithubConnectionRepository.class);
    private final TokenCipher cipher = new TokenCipher("gap-fill-key", "0123456789abcdef");

    private HttpServer server;
    private AiGapFillAdapter adapter;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestToken;
    private volatile String requestBody;

    @BeforeEach
    void setUp() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestToken = exchange.getRequestHeaders().getFirst("X-GitHub-Token");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        when(users.findById(USER_ID)).thenReturn(Optional.of(User.register(42L, "grow22")));
        GithubConnection connection = GithubConnection.grant(USER_ID, new String[]{"read:user"}, cipher.encrypt(TOKEN),
                null);
        when(connections.findByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(Optional.of(connection));
        var properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .readTimeout(Duration.ofSeconds(5)).build();
        adapter = new AiGapFillAdapter(new GithubCollectionAccess(users, connections, cipher,
                new AiHttpClient(properties, MAPPER), null, null, Clock.systemUTC(), mock(GithubDisconnectService.class),
                null, properties));

    }

    @AfterEach
    void stopServer() {

        server.stop(0);

    }

    @Test
    @DisplayName("토큰은 헤더에만 실어 /internal/matching/gap-fill/stream 로 보내고, 요청은 AI 계약의 이름(snake_case)이며 id 는 공개 id 다")
    void streamsWithTokenOnlyInHeaderAndSnakeCaseBody() {

        responseBody = result(List.of(condition(CONDITION_A, "null", "NOT_FOUND"),
                condition(CONDITION_B, "null", "SKIPPED")));

        adapter.fill(USER_ID, request(), condition -> { });

        assertThat(requestPath).isEqualTo("/internal/matching/gap-fill/stream");
        assertThat(requestToken).isEqualTo(TOKEN);
        assertThat(requestBody).doesNotContain(TOKEN);
        assertThat((Object) MAPPER.readTree(requestBody)).isEqualTo(MAPPER.readTree("""
                {
                  "company_name": "나래랩스",
                  "job_title": "서버 개발자",
                  "conditions": [
                    {"condition_id": "%s", "criterion_type": "REQUIRED", "text": "Kafka 로 메시지를 처리해 본 경험"},
                    {"condition_id": "%s", "criterion_type": "REQUIRED", "text": "대용량 트래픽 처리 경험"}
                  ],
                  "cards": [{"card_id": "%s", "title": "결제 API", "action": "Spring 으로 만들었다", "techs": ["SPRING"]}],
                  "candidates": [{"candidate_id": "%s", "repo_name": "grow22/pay", "title": "주문 큐",
                                  "reason": "큐를 붙였다", "commit_messages": ["feat: 주문 큐"]}],
                  "draft_cards": [{"card_id": "%s", "title": "캐시 도입", "techs": []}],
                  "repos": [{"repo_id": "%s", "full_name": "grow22/pay", "analyzed": true},
                            {"repo_id": "%s", "full_name": "grow22/shop", "analyzed": false}],
                  "github_login": "grow22",
                  "force_condition_ids": ["%s"]
                }
                """.formatted(CONDITION_A, CONDITION_B, CARD, CANDIDATE, DRAFT, ANALYZED_REPO, NEW_REPO, CONDITION_B)));

    }

    @Test
    @DisplayName("진행 줄의 조건 상태(다시 찾게 한 표시 forced 포함)는 온 순서대로 넘기고, 모르는 조건·틀린 단계 종류·보내지 않았거나 분석한 레포·종류가 다른 id 를 가리키는 줄은 버린다")
    void forwardsValidLiveConditionsAndDropsMalformedOnes() {

        String searching = """
                {"condition_id": "%s", "decision": "SEARCH", "decision_reason": "관련 있음", "forced": true,
                 "steps": [{"kind": "CANDIDATES_CHECKED", "count": 3, "query": null, "repo_name": null}],
                 "proposal": null, "result": null}""".formatted(CONDITION_A);
        String continueDraft = condition(CONDITION_B, proposal("CONTINUE_CARD", DRAFT), "PROPOSED");
        String notFound = condition(CONDITION_A, "null", "NOT_FOUND");
        responseBody = String.join("\n",
                live(searching),
                live(condition(UUID.randomUUID(), "null", null)),
                live("""
                        {"condition_id": "%s", "decision": "SEARCH", "decision_reason": null,
                         "steps": [{"kind": "ASKED_USER", "count": null, "query": null, "repo_name": null}],
                         "proposal": null, "result": null}""".formatted(CONDITION_A)),
                live(condition(CONDITION_B, proposal("ANALYZE_REPO", ANALYZED_REPO), "PROPOSED")),
                live(condition(CONDITION_B, proposal("ANALYZE_REPO", UUID.randomUUID()), "PROPOSED")),
                live(condition(CONDITION_B, proposal("CREATE_CARD", DRAFT), "PROPOSED")),
                "{\"type\":\"progress\",\"step\":\"GAP_FILL\",\"done\":0,\"total\":2}",
                live(continueDraft),
                live(notFound),
                result(List.of(notFound, continueDraft)));
        List<GapFillCondition> received = new ArrayList<>();

        List<GapFillCondition> filled = adapter.fill(USER_ID, request(), received::add);

        GapFillCondition proposed = new GapFillCondition(CONDITION_B, GapFillDecision.SEARCH, "관련 있음", false, List.of(),
                new GapFillProposal(GapFillAction.CONTINUE_CARD, DRAFT, null, null, null, "이 카드에 이어 쓸 수 있다"),
                GapFillResult.PROPOSED);
        GapFillCondition finished = new GapFillCondition(CONDITION_A, GapFillDecision.SEARCH, "관련 있음", false, List.of(),
                null, GapFillResult.NOT_FOUND);
        assertThat(received).containsExactly(new GapFillCondition(CONDITION_A, GapFillDecision.SEARCH, "관련 있음", true,
                List.of(new GapFillStep(GapFillStepKind.CANDIDATES_CHECKED, 3, null, null)), null, null),
                proposed, finished);
        assertThat(filled).containsExactly(finished, proposed);

    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_CONDITION", "UNSENT_CANDIDATE", "AI_FAILED"})
    @DisplayName("마지막 응답이 보낸 조건과 다르거나 보내지 않은 id 를 가리키거나 AI 가 실패를 알리면 MatchingAiUnavailableException 이다")
    void malformedOrFailedFinalResponseIsAiUnavailable(String failure) {

        String notFound = condition(CONDITION_A, "null", "NOT_FOUND");
        responseBody = switch (failure) {
            case "MISSING_CONDITION" -> result(List.of(notFound));
            case "UNSENT_CANDIDATE" -> result(List.of(notFound,
                    condition(CONDITION_B, proposal("CREATE_CARD", UUID.randomUUID()), "PROPOSED")));
            default -> """
                    {"type":"result","status":503,"body":{"success":false,"data":null,"meta":null,
                     "error":{"code":"LLM_UNAVAILABLE","message":"실패","retryable":true}}}""".replace("\n", "");
        };

        assertThatThrownBy(() -> adapter.fill(USER_ID, request(), condition -> { }))
                .isInstanceOf(MatchingAiUnavailableException.class)
                .hasMessageNotContaining(TOKEN);

    }

    private static GapFillRequest request() {

        return new GapFillRequest("나래랩스", "서버 개발자",
                List.of(new GapFillRequest.Condition(CONDITION_A, CriterionType.REQUIRED, "Kafka 로 메시지를 처리해 본 경험"),
                        new GapFillRequest.Condition(CONDITION_B, CriterionType.REQUIRED, "대용량 트래픽 처리 경험")),
                List.of(new GapFillRequest.Card(CARD, "결제 API", null, null, "Spring 으로 만들었다", null,
                        List.of("SPRING"))),
                List.of(new GapFillRequest.Candidate(CANDIDATE, "grow22/pay", "주문 큐", "큐를 붙였다",
                        List.of("feat: 주문 큐"))),
                List.of(new GapFillRequest.Card(DRAFT, "캐시 도입", null, null, null, null, List.of())),
                List.of(new GapFillRequest.Repo(ANALYZED_REPO, "grow22/pay", true),
                        new GapFillRequest.Repo(NEW_REPO, "grow22/shop", false)),
                "grow22", List.of(CONDITION_B));

    }

    private static String condition(UUID id, String proposal, String result) {

        return """
                {"condition_id": "%s", "decision": "SEARCH", "decision_reason": "관련 있음", "steps": [],
                 "proposal": %s, "result": %s}""".formatted(id, proposal,
                result == null ? "null" : "\"" + result + "\"");

    }

    private static String proposal(String action, UUID target) {

        return "{\"action\": \"%s\", \"target_id\": \"%s\", \"reason\": \"이 카드에 이어 쓸 수 있다\"}"
                .formatted(action, target);

    }

    private static String live(String condition) {

        return "{\"type\":\"progress\",\"step\":\"GAP_FILL\",\"done\":0,\"total\":2,"
                + "\"item\":{\"kind\":\"GAP_CONDITION\",\"condition\":" + condition.replace("\n", "") + "}}";

    }

    private static String result(List<String> conditions) {

        return "{\"type\":\"result\",\"status\":200,\"body\":{\"success\":true,\"error\":null,\"meta\":null,"
                + "\"data\":{\"conditions\":[" + String.join(",", conditions).replace("\n", "") + "]}}}\n";

    }
}
