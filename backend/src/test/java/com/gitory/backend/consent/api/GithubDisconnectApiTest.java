package com.gitory.backend.consent.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.infra.GithubGrantClient;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class GithubDisconnectApiTest {

    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TokenCipher cipher;

    @MockitoBean
    GithubGrantClient grants;

    private Long myUserId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void requiresLogin() throws Exception {

        mvc.perform(post("/api/github/disconnect").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 403 이고 연결은 그대로다")
    void requiresCsrfToken() throws Exception {

        connect();

        mvc.perform(post("/api/github/disconnect").with(loggedIn()))
                .andExpect(status().isForbidden());

        assertThat(connection().get("revoked_at")).isNull();
        verifyNoInteractions(grants);

    }

    @Test
    @DisplayName("연결을 해제하면 DB 의 토큰을 지우고, 지우기 전의 토큰으로 GitHub 앱 권한 삭제를 요청한다")
    void revokesConnectionAndDeletesGrant() throws Exception {

        connect();

        disconnect()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        Map<String, Object> connection = connection();
        assertThat(connection.get("token_enc")).isNull();
        assertThat(connection.get("revoked_at")).isNotNull();
        verify(grants).deleteGrant(TOKEN);

    }

    @Test
    @DisplayName("이미 해제된 상태에서 또 요청해도 200 ok 이고 GitHub 은 부르지 않는다")
    void disconnectingTwiceIsOk() throws Exception {

        disconnect()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        verifyNoInteractions(grants);

    }

    @Test
    @DisplayName("GitHub 앱 권한 삭제가 실패해도 DB 의 토큰은 지우고 200 ok 다")
    void revokesEvenWhenGithubFails() throws Exception {

        connect();
        willThrow(new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY)).given(grants).deleteGrant(anyString());

        disconnect()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        assertThat(connection().get("token_enc")).isNull();

    }

    @Test
    @DisplayName("해제한 뒤 내 정보는 GitHub 연결 안 됨으로 바뀐다")
    void meShowsDisconnectedAfterwards() throws Exception {

        connect();

        disconnect();

        mvc.perform(get("/api/me").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.github.connected").value(false));

    }

    private void connect() {

        // granted_at 을 DB 기본값(now())에 맡기면 Docker 시계가 자바 시계보다 조금 빠를 때 revoked_at 이 더 이르게 되어 CHECK 에 걸린다
        jdbc.update("INSERT INTO github_connection (user_id, scopes, token_enc, granted_at) VALUES (?, ARRAY['read:user'], ?, ?)",
                myUserId, cipher.encrypt(TOKEN), Timestamp.from(Instant.now().minusSeconds(60)));

    }

    private Map<String, Object> connection() {

        return jdbc.queryForMap("SELECT token_enc, revoked_at FROM github_connection WHERE user_id = ?", myUserId);

    }

    private ResultActions disconnect() throws Exception {

        return mvc.perform(post("/api/github/disconnect").with(loggedIn()).with(csrf()));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
