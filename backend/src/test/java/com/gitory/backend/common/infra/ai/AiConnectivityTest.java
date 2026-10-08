package com.gitory.backend.common.infra.ai;

import com.gitory.backend.common.api.AiDocumentationController;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 HTTP 응답으로 BE 연결 상태와 문서 전송을 검증한다. GitHub·LLM은 호출하지 않는다. */
class AiConnectivityTest {

    private HttpServer server;
    private AiHttpClient client;
    private final AtomicReference<String> response = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        client = new AiHttpClient(new AiClientProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)), JsonMapper.builder().build());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void matchingAiProcessIsUp() {
        response.set("{\"status\":\"ok\",\"service\":\"gitory-ai\",\"version\":\"0.0.1\"}");
        assertThat(new AiHealthIndicator(client).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void wrongServiceCannotAppearHealthy() {
        response.set("{\"status\":\"ok\",\"service\":\"another-service\"}");
        assertThat(new AiHealthIndicator(client).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void unreachableAiIsDownWithoutNetworkOrResponseDetails() {
        server.stop(0);
        var health = new AiHealthIndicator(client).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsOnlyKeys("code");
        assertThat(health.getDetails().get("code")).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void aiDocumentIsRawOpenApiInsteadOfBackendEnvelope() throws Exception {
        response.set("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"AI\",\"version\":\"0.0.1\"},\"paths\":{}}");
        documentMvc().perform(get("/api/docs/ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void readOnlyViewerDoesNotRequireAiToBeReachable() throws Exception {
        server.stop(0);
        documentMvc().perform(get("/api/ai-docs.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("supportedSubmitMethods: []")));
    }

    @Test
    void nonOpenApiResponseCannotBeReturnedAsADocument() throws Exception {
        response.set("{\"unexpected\":\"private upstream message\"}");
        var result = documentMvc().perform(get("/api/docs/ai"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private upstream message");
    }

    private MockMvc documentMvc() {
        return MockMvcBuilders.standaloneSetup(new AiDocumentationController(client)).build();
    }
}
