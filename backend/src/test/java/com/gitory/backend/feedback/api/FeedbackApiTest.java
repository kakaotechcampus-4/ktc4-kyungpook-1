package com.gitory.backend.feedback.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.rate-limit.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
class FeedbackApiTest {

    private static final String LEAK = "leak-marker-글";
    private static final List<String> WEBHOOK_BODIES = new CopyOnWriteArrayList<>();
    private static final HttpServer WEBHOOK = startWebhook();
    private static volatile int webhookStatus = 204;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void webhook(DynamicPropertyRegistry registry) {

        registry.add("gitory.alert.webhook-url", () -> "http://127.0.0.1:" + WEBHOOK.getAddress().getPort() + "/hook");

    }

    @AfterAll
    static void stopWebhook() {

        WEBHOOK.stop(0);

    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    private Long myUserId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        redis.delete(redis.keys("rate:*"));
        WEBHOOK_BODIES.clear();
        webhookStatus = 204;
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("F1 정상 신고는 사용자·글·화면 주소·User-Agent 를 앞뒤 공백 없이 저장하고 200 에 data 는 null 이다")
    void storesValidReport() throws Exception {

        String longAgent = "A".repeat(300);

        send("{\"message\":\"  카드가 안 만들어져요  \",\"path\":\"/repos\"}", longAgent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("SELECT user_id, message, path, user_agent, created_at FROM feedback");
        assertThat(row.get("user_id")).isEqualTo(myUserId);
        assertThat(row.get("message")).isEqualTo("카드가 안 만들어져요");
        assertThat(row.get("path")).isEqualTo("/repos");
        assertThat(row.get("user_agent")).isEqualTo("A".repeat(255));
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isBefore(Instant.now().plusSeconds(5));

    }

    @Test
    @DisplayName("F2 빈 글·1000자 초과 글·255자 초과 화면 주소는 400 이고 저장되지 않는다")
    void rejectsInvalidBodies() throws Exception {

        send("{\"message\":\"   \",\"path\":\"/\"}", "agent").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        send("{\"message\":\"" + "가".repeat(1001) + "\",\"path\":\"/\"}", "agent").andExpect(status().isBadRequest());
        send("{\"message\":\"안 돼요\",\"path\":\"/" + "a".repeat(255) + "\"}", "agent").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback", Integer.class)).isZero();

    }

    @Test
    @DisplayName("F3 한 사용자의 11번째 신고는 하루 한도로 429 이고 저장되지 않는다")
    void eleventhReportOfTheDayIsRateLimited() throws Exception {

        for (int i = 0; i < 10; i++) {
            send("{\"message\":\"신고 " + i + "\",\"path\":\"/\"}", "agent").andExpect(status().isOk());
        }

        send("{\"message\":\"열한 번째\",\"path\":\"/\"}", "agent")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback", Integer.class)).isEqualTo(10);

    }

    @Test
    @DisplayName("F4 저장되면 Discord 에 번호만 담은 한 줄이 가고 사용자가 쓴 글은 가지 않는다")
    void sendsOnlyAnIdLineToDiscord() throws Exception {

        send("{\"message\":\"" + LEAK + "\",\"path\":\"/\"}", "agent").andExpect(status().isOk());

        Long id = jdbc.queryForObject("SELECT id FROM feedback", Long.class);
        assertThat(WEBHOOK_BODIES).hasSize(1);
        assertThat(WEBHOOK_BODIES.get(0)).isEqualTo("{\"content\":\"[Gitory] 문제 신고가 들어왔습니다 (#" + id + ")\"}");
        assertThat(WEBHOOK_BODIES.get(0)).doesNotContain("leak-marker");

    }

    @Test
    @DisplayName("F4 Discord 로 보내지 못해도 요청은 200 이고 저장한 신고는 남는다")
    void failedDiscordSendKeepsTheReport() throws Exception {

        webhookStatus = 500;

        send("{\"message\":\"안 돼요\",\"path\":\"/\"}", "agent").andExpect(status().isOk());

        assertThat(WEBHOOK_BODIES).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback", Integer.class)).isEqualTo(1);

    }

    @Test
    @DisplayName("F5 로그인하지 않으면 401 이고 저장되지 않는다")
    void requiresLogin() throws Exception {

        mvc.perform(post("/api/feedback").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안 돼요\",\"path\":\"/\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback", Integer.class)).isZero();

    }

    @Test
    @DisplayName("F6 사용자 행을 지우면 그 사용자의 신고도 함께 지워진다")
    void deletingTheUserDeletesTheirReports() throws Exception {

        send("{\"message\":\"안 돼요\",\"path\":\"/\"}", "agent").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback", Integer.class)).isEqualTo(1);

        jdbc.update("DELETE FROM users WHERE id = ?", myUserId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback", Integer.class)).isZero();

    }

    private ResultActions send(String body, String userAgent) throws Exception {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return mvc.perform(post("/api/feedback")
                .with(authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())))
                .with(csrf())
                .header("User-Agent", userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

    }

    private static HttpServer startWebhook() {

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                WEBHOOK_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(webhookStatus, -1);
                exchange.close();
            });
            server.start();

            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

    }
}
