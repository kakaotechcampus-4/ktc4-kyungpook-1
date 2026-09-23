package com.gitory.backend.dependency;

import com.gitory.backend.consent.domain.StubGithubConfig;
import com.gitory.backend.consent.infra.GithubConnectionRepository;
import com.gitory.backend.consent.infra.UserRepository;
import com.gitory.backend.support.TestBrowser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션이 메모리가 아니라 DB 에 저장되는지 본다.
 *
 * #17 은 spring-session-jdbc 만 넣고 스타터를 빠뜨려 store-type: jdbc 가
 * 조용히 무시된 사고였다. 로그인은 멀쩡히 됐다 — 세션이 메모리에 있었을 뿐이고,
 * 인스턴스를 재시작하면 전원이 로그아웃되는 상태였다. 그때도 테스트가 못 잡은 게 아니라
 * 세션이 어디 있는지 보는 테스트가 없었다.
 *
 * 그래서 여기서는 로그인 성공 여부가 아니라 SPRING_SESSION 테이블의 행을 센다.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Import(StubGithubConfig.class)
@Testcontainers
class SessionStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    GithubConnectionRepository connections;

    private TestBrowser browser;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM SPRING_SESSION");
        connections.deleteAll();
        users.deleteAll();
        browser = new TestBrowser(mvc);
    }

    @Test
    @DisplayName("로그인하면 SPRING_SESSION 에 행이 생긴다 — 세션은 메모리가 아니라 DB 에 있다")
    void loginWritesTheSessionToTheDatabase() throws Exception {
        assertThat(sessionCount()).isZero();

        login();

        assertThat(sessionCount())
                .as("로그인했는데 DB 에 세션이 없다 — 세션이 메모리에 남아 있다는 뜻이다 (#17)")
                .isEqualTo(1);
        assertThat(browser.hasCookie("SESSION"))
                .as("세션 쿠키가 내려와야 브라우저가 다음 요청에 세션을 이어 붙인다")
                .isTrue();
    }

    @Test
    @DisplayName("세션 행의 PRINCIPAL_NAME 이 로그인한 사용자 id 다 — 껍데기만 저장된 게 아니다")
    void theStoredSessionBelongsToTheLoggedInUser() throws Exception {
        login();

        Long userId = users.findByGithubUserId(StubGithubConfig.GITHUB_USER_ID).orElseThrow().getId();

        String principal = jdbc.queryForObject("SELECT PRINCIPAL_NAME FROM SPRING_SESSION", String.class);
        assertThat(principal).isEqualTo(String.valueOf(userId));

        // 인증 정보가 실제로 직렬화돼 들어갔다는 증거. 이 행이 없으면 다음 요청은 다시 401 이다.
        assertThat(attributeCount()).isPositive();
    }

    @Test
    @DisplayName("로그아웃하면 세션 행과 속성 행이 함께 지워진다")
    void logoutRemovesTheSessionRow() throws Exception {
        login();
        assertThat(sessionCount()).isEqualTo(1);

        browser.perform(post("/api/auth/logout").header("X-XSRF-TOKEN", browser.csrfToken()));

        assertThat(sessionCount()).isZero();
        assertThat(attributeCount()).as("SPRING_SESSION_ATTRIBUTES 는 FK ON DELETE CASCADE 로 함께 지워진다")
                .isZero();
    }

    @Test
    @DisplayName("두 브라우저가 각각 로그인하면 세션 행도 두 개가 된다 — 한 세션을 공유하지 않는다")
    void eachBrowserGetsItsOwnSessionRow() throws Exception {
        login();
        browser.clearCookies();
        login();

        assertThat(sessionCount()).isEqualTo(2);
        assertThat(users.count()).as("사용자는 같은 사람이다").isEqualTo(1);
    }

    private int sessionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM SPRING_SESSION", Integer.class);
    }

    private int attributeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM SPRING_SESSION_ATTRIBUTES", Integer.class);
    }

    private void login() throws Exception {
        String authorizeUrl = browser.perform(get("/api/auth/github/start"))
                .andReturn().getResponse().getRedirectedUrl();

        browser.perform(get("/api/auth/github/callback")
                .param("code", "stub-authorization-code")
                .param("state", TestBrowser.queryOf(authorizeUrl).get("state")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
