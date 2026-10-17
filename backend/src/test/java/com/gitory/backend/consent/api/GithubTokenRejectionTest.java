package com.gitory.backend.consent.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.infra.GithubGrantClient;
import com.gitory.backend.consent.infra.GithubRepositoryClient;
import com.gitory.backend.job.domain.AnalysisJob;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class GithubTokenRejectionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AnalysisJobRepository jobs;

    @MockitoBean
    GithubRepositoryClient githubRepositories;

    @MockitoBean
    GithubGrantClient grants;

    private Long myUserId;
    private Long myConnectionId;
    private UUID myRepo;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        myConnectionId = fixtures.insertGithubConnection(myUserId, "gho_StubbedAccessTokenExample1234567");
        myRepo = UUID.randomUUID();
        fixtures.insertUserRepository(myRepo, myUserId, fixtures.insertRepository(100L, "grow22", "gitory"));

    }

    @Test
    @DisplayName("저장소 목록을 받다가 GitHub 이 토큰을 거절하면 200 으로 저장된 저장소를 주고 연결은 해제로 바뀐다")
    void rejectedTokenListsStoredRepositoriesAndRevokes() throws Exception {

        githubRejects(401);

        repos()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("gitory"));

        Map<String, Object> connection = connection();
        assertThat(connection.get("revoked_at")).isNotNull();
        assertThat(connection.get("token_enc")).isNull();

    }

    @Test
    @DisplayName("토큰 거절로 해제하면 그 연결로 REVOKE 성공 기록이 하나 남는다")
    void rejectedTokenLeavesOneRevokeRecord() throws Exception {

        githubRejects(401);

        repos().andExpect(status().isOk());
        repos().andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT action || ' ' || outcome || ' ' || subject_id FROM audit_event", String.class))
                .containsExactly("REVOKE OK " + myConnectionId);

    }

    @Test
    @DisplayName("토큰 거절로 해제할 때는 GitHub 앱 권한 삭제를 요청하지 않는다")
    void rejectedTokenDoesNotCallGrantApi() throws Exception {

        githubRejects(401);

        repos().andExpect(status().isOk());

        verifyNoInteractions(grants);

    }

    @Test
    @DisplayName("해제로 바뀐 뒤의 목록 요청은 GitHub 을 다시 부르지 않고 저장된 저장소를 준다")
    void afterRevokeListDoesNotCallGithub() throws Exception {

        githubRejects(401);

        repos().andExpect(status().isOk());
        repos().andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));

        verify(githubRepositories, times(1)).fetchRepositories(anyString());

    }

    @Test
    @DisplayName("토큰 거절로 해제한 뒤 내 정보는 연결 안 됨으로 바뀌고 저장된 저장소는 그대로다")
    void meShowsDisconnectedAfterRejection() throws Exception {

        githubRejects(401);
        repos().andExpect(status().isOk());

        mvc.perform(get("/api/me").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.github.connected").value(false))
                .andExpect(jsonPath("$.data.github.scopes").value(empty()))
                .andExpect(jsonPath("$.data.github.connectedAt").value(nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_repository WHERE user_id = ?", Integer.class, myUserId))
                .isOne();

    }

    @Test
    @DisplayName("해제로 바뀐 뒤에도 DB 만 읽는 저장소 상세와 Job 조회는 200 이다")
    void databaseOnlyApisKeepWorkingAfterRejection() throws Exception {

        AnalysisJob job = jobs.save(AnalysisJob.enqueue(myUserId, userRepositoryId(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        githubRejects(401);
        repos().andExpect(status().isOk());

        mvc.perform(get("/api/repos/{id}", myRepo).with(loggedIn())).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/{id}", job.getPublicId()).with(loggedIn())).andExpect(status().isOk());

    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    @DisplayName("GitHub 이 사용 한도로 거절하면 200 으로 저장된 저장소를 주고 연결도 감사 기록도 그대로다")
    void rateLimitedKeepsConnection(int statusCode) throws Exception {

        githubRejects(statusCode);

        repos()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("gitory"));

        Map<String, Object> connection = connection();
        assertThat(connection.get("revoked_at")).isNull();
        assertThat(connection.get("token_enc")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isZero();

    }

    private void githubRejects(int statusCode) {

        given(githubRepositories.fetchRepositories(anyString())).willThrow(HttpClientErrorException.create(
                HttpStatusCode.valueOf(statusCode), "rejected", new HttpHeaders(), new byte[0], null));

    }

    private Long userRepositoryId() {

        return jdbc.queryForObject("SELECT id FROM user_repository WHERE public_id = ?", Long.class, myRepo);

    }

    private Map<String, Object> connection() {

        return jdbc.queryForMap("SELECT token_enc, revoked_at FROM github_connection WHERE id = ?", myConnectionId);

    }

    private ResultActions repos() throws Exception {

        return mvc.perform(get("/api/repos").with(loggedIn()));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
