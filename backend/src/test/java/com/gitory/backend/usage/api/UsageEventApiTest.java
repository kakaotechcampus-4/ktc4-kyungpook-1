package com.gitory.backend.usage.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import com.gitory.backend.usage.domain.UsageEventRejection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static com.gitory.backend.usage.domain.UsageEventRejection.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UsageEventApiTest {

    private static final String AT = "2026-10-18T01:00:00Z";
    private static final String LEAK = "leak-marker";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Long myUserId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("세션만 있고 CSRF 헤더가 없어도 허용된 이름이면 200 이고 보낸 값이 그대로 저장된다")
    void storesAllowedEventWithoutCsrfHeader() throws Exception {

        send("{\"name\":\"repo_selected\",\"props\":{\"repoId\":\"abc\",\"count\":3,\"first\":true,\"memo\":null},\"at\":\"" + AT + "\",\"path\":\"/repos\"}",
                MediaType.APPLICATION_JSON, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, name, props ->> 'repoId' AS repo_id, props ->> 'count' AS cnt, props ->> 'first' AS first, path, client_at FROM usage_event");
        assertThat(row.get("user_id")).isEqualTo(myUserId);
        assertThat(row.get("name")).isEqualTo("repo_selected");
        assertThat(row.get("repo_id")).isEqualTo("abc");
        assertThat(row.get("cnt")).isEqualTo("3");
        assertThat(row.get("first")).isEqualTo("true");
        assertThat(row.get("path")).isEqualTo("/repos");
        assertThat(((Timestamp) row.get("client_at")).toInstant()).isEqualTo(Instant.parse(AT));

    }

    @Test
    @DisplayName("props 를 빼고 보내도 200 이고 빈 객체로 저장된다")
    void storesEmptyPropsWhenOmitted() throws Exception {

        send("{\"name\":\"repo_selected\",\"at\":\"" + AT + "\",\"path\":\"/\"}", MediaType.APPLICATION_JSON, true)
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT props::text FROM usage_event", String.class)).isEqualTo("{}");

    }

    @Test
    @DisplayName("로그인하지 않으면 401 이고 저장되지 않는다")
    void requiresLogin() throws Exception {

        send(valid("repo_selected"), MediaType.APPLICATION_JSON, false)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        assertThat(rowCount()).isZero();

    }

    @Test
    @DisplayName("허용 목록에 없는 이름은 400 이고 저장되지 않는다")
    void rejectsUnknownName() throws Exception {

        send(valid("not_in_list"), MediaType.APPLICATION_JSON, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(rowCount()).isZero();

    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"not_in_list,not_in_list", "INTERNAL_ERROR,(이름 모양이 아님)", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,(이름 모양이 아님)"})
    @DisplayName("모르는 이름은 소문자·숫자·밑줄 40자 이하일 때만 로그에 남기고 아니면 고정 글만 남긴다")
    void logsOnlyWellFormedUnknownName(String sentName, String expectedLogged, CapturedOutput output) throws Exception {

        send(valid(sentName), MediaType.APPLICATION_JSON, true).andExpect(status().isBadRequest());

        assertThat(output.getOut()).contains("사용 지표를 거절했다: 이유=UNKNOWN_NAME, 이름=" + expectedLogged);
        if (!sentName.equals(expectedLogged)) {
            assertThat(output.getOut()).doesNotContain(sentName);
        }

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("칸 상한을 넘거나 필수 칸이 없거나 JSON 이 아니면 거절하고 저장하지 않는다")
    void rejectsInvalidBody(String caseName, String body, MediaType contentType, int expectedStatus, UsageEventRejection reason) throws Exception {

        send(body, contentType, true)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(rowCount()).isZero();

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("거절할 때마다 이유 코드 한 줄만 로그에 남기고 보낸 값은 남기지 않는다")
    void logsOneReasonLineWithoutSentValues(String caseName, String body, MediaType contentType, int expectedStatus, UsageEventRejection reason, CapturedOutput output) throws Exception {

        send(body, contentType, true).andExpect(status().is(expectedStatus));

        assertThat(output.getOut()).containsOnlyOnce("사용 지표를 거절했다");
        assertThat(output.getOut()).contains("사용 지표를 거절했다: 이유=" + reason);
        assertThat(output.getOut()).doesNotContain(LEAK);

    }

    static Stream<Arguments> invalidBodies() {

        MediaType jsonType = MediaType.APPLICATION_JSON;
        String head = "{\"name\":\"repo_selected\",";
        String tail = "\"at\":\"" + AT + "\",\"path\":\"/" + LEAK + "\"}";
        return Stream.of(
                Arguments.of("props 가 2KB 초과", head + "\"props\":{\"k\":\"" + LEAK + "x".repeat(2030) + "\"}," + tail, jsonType, 400, PROPS_TOO_LARGE),
                Arguments.of("props 값이 객체", head + "\"props\":{\"" + LEAK + "\":{\"a\":1}}," + tail, jsonType, 400, PROPS_VALUE_TYPE),
                Arguments.of("props 값이 배열", head + "\"props\":{\"" + LEAK + "\":[1]}," + tail, jsonType, 400, PROPS_VALUE_TYPE),
                Arguments.of("path 255자 초과", head + "\"props\":{},\"at\":\"" + AT + "\",\"path\":\"" + LEAK + "p".repeat(245) + "\"}", jsonType, 400, PATH_INVALID),
                Arguments.of("at 없음", head + "\"props\":{},\"path\":\"/" + LEAK + "\"}", jsonType, 400, AT_INVALID),
                Arguments.of("name 없음", "{\"props\":{},\"at\":\"" + AT + "\",\"path\":\"/" + LEAK + "\"}", jsonType, 400, UNKNOWN_NAME),
                Arguments.of("path 없음", head + "\"props\":{\"k\":\"" + LEAK + "\"},\"at\":\"" + AT + "\"}", jsonType, 400, PATH_INVALID),
                Arguments.of("path 에 U+0000", head + "\"props\":{},\"at\":\"" + AT + "\",\"path\":\"/" + LEAK + "\\u0000b\"}", jsonType, 400, PATH_INVALID),
                Arguments.of("props 값에 U+0000", head + "\"props\":{\"k\":\"" + LEAK + "\\u0000b\"}," + tail, jsonType, 400, PROPS_NUL),
                Arguments.of("props 키에 U+0000", head + "\"props\":{\"" + LEAK + "\\u0000b\":1}," + tail, jsonType, 400, PROPS_NUL),
                Arguments.of("at 이 9999년 이후", head + "\"props\":{},\"at\":\"+10000-01-01T00:00:00Z\",\"path\":\"/" + LEAK + "\"}", jsonType, 400, AT_INVALID),
                Arguments.of("at 이 1970년 이전", head + "\"props\":{},\"at\":\"1969-12-31T23:59:59Z\",\"path\":\"/" + LEAK + "\"}", jsonType, 400, AT_INVALID),
                Arguments.of("at 가 시각 모양이 아님", head + "\"props\":{},\"at\":\"" + LEAK + "\",\"path\":\"/\"}", jsonType, 400, BODY_UNREADABLE),
                Arguments.of("깨진 JSON", head + "\"props\":{\"k\":\"" + LEAK, jsonType, 400, BODY_UNREADABLE),
                Arguments.of("text/plain 본문", head + "\"props\":{}," + tail, MediaType.TEXT_PLAIN, 415, UNSUPPORTED_MEDIA_TYPE));

    }

    private static String valid(String name) {

        return "{\"name\":\"" + name + "\",\"props\":{},\"at\":\"" + AT + "\",\"path\":\"/\"}";

    }

    private ResultActions send(String body, MediaType contentType, boolean loggedIn) throws Exception {

        var request = post("/api/events").contentType(contentType).content(body);
        if (loggedIn) {
            request = request.with(loggedIn());
        }

        return mvc.perform(request);

    }

    private Integer rowCount() {

        return jdbc.queryForObject("SELECT count(*) FROM usage_event", Integer.class);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
