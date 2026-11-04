package com.gitory.backend.agent.infra;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.RepoSummaryRequest;
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

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRepoSummaryAdapterTest {

    private static final RepoSummaryRequest REQUEST = new RepoSummaryRequest(7L, "grow22", "gitory", "Java",
            List.of("README.md", "build.gradle", "src"), "# gitory");

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody;
    private volatile String requestPath;
    private volatile String requestBody;

    @BeforeEach
    void setUp() throws Exception {

        responseBody = success("{\"repo_id\":7,\"summary\":\"커밋 기록으로 경험 카드를 만들어 주는 서비스예요.\"}");
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
    @DisplayName("요청 본문이 계약 필드명(snake_case)으로 나가고, 받은 문장을 앞뒤 공백을 떼어 돌려준다")
    void postsContractFieldsAndReadsSentence() {

        responseBody = success("{\"repo_id\":7,\"summary\":\"  커밋 기록으로 경험 카드를 만들어 주는 서비스예요.  \"}");

        assertThat(adapter().summarize(REQUEST)).contains("커밋 기록으로 경험 카드를 만들어 주는 서비스예요.");

        JsonNode sent = mapper.readTree(requestBody);
        assertThat(requestPath).isEqualTo("/internal/repo-summary");
        assertThat(sent.propertyNames())
                .containsExactlyInAnyOrder("repo_id", "owner", "name", "language", "top_level_files", "readme_head");
        assertThat(sent.path("repo_id").asLong()).isEqualTo(7L);
        assertThat(sent.path("owner").asString()).isEqualTo("grow22");
        assertThat(sent.path("name").asString()).isEqualTo("gitory");
        assertThat(sent.path("language").asString()).isEqualTo("Java");
        assertThat(sent.path("top_level_files")).extracting(JsonNode::asString)
                .containsExactly("README.md", "build.gradle", "src");
        assertThat(sent.path("readme_head").asString()).isEqualTo("# gitory");

    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    @DisplayName("summary 가 null 이거나 빈 글이면 재료가 모자란 것이라 실패가 아니라 빈 값이다")
    void emptySummaryMeansNotEnoughMaterial(String summary) {

        responseBody = success("{\"repo_id\":7,\"summary\":" + summary + "}");

        assertThat(adapter().summarize(REQUEST)).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"repo_id\":8,\"summary\":\"다른 저장소의 문장이에요.\"}",
            "{\"summary\":\"repo_id 가 없는 문장이에요.\"}",
            "{\"repo_id\":7,\"summary\":42}",
            "{\"repo_id\":7}"})
    @DisplayName("repo_id 가 다르거나 없고 summary 가 글도 null 도 아니면 다시 해도 같은 이상 응답이다")
    void rejectsResponsesOutsideTheContract(String data) {

        responseBody = success(data);

        assertThatThrownBy(() -> adapter().summarize(REQUEST))
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

        assertThatThrownBy(() -> adapter().summarize(REQUEST))
                .isInstanceOfSatisfying(AgentUnavailableException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.isInvalidResponse()).isFalse();
                });

    }

    private String success(String data) {

        return "{\"success\":true,\"data\":" + data + ",\"meta\":{\"processing_ms\":12},\"error\":null}";

    }

    private AiRepoSummaryAdapter adapter() {

        AiClientProperties properties = AiTestProperties.defaults()
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        return new AiRepoSummaryAdapter(new AiHttpClient(properties, mapper));

    }
}
