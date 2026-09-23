package com.gitory.backend.job.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class AnalyzeApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MY_OTHER_REPO = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHERS_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final String KEY_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String KEY_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

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

        jdbc.execute("TRUNCATE users, repository CASCADE");

        myUserId = insertUser(1L, "grow22");
        Long othersUserId = insertUser(2L, "someone-else");

        Long repositoryId = insertRepository(1L, "gitory");
        Long otherRepositoryId = insertRepository(2L, "gitory-docs");

        connect(MY_REPO, myUserId, repositoryId);
        connect(MY_OTHER_REPO, myUserId, otherRepositoryId);
        connect(OTHERS_REPO, othersUserId, repositoryId);
    }

    @Test
    @DisplayName("분석을 요청하면 jobId 와 함께 QUEUED 상태가 내려온다")
    void startsAnalysis() throws Exception {

        analyze(MY_REPO, KEY_1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.jobId").isString())
                .andExpect(jsonPath("$.data.state").value("QUEUED"))
                .andExpect(jsonPath("$.data.pollAfterMs").value(2000));
    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 다시 요청해도 Job 은 하나다")
    void sameKeyReturnsSameJob() throws Exception {

        String first = jobIdOf(analyze(MY_REPO, KEY_1));
        String second = jobIdOf(analyze(MY_REPO, KEY_1));

        assertThat(second).isEqualTo(first);
        assertThat(jobCount()).isEqualTo(1);

    }

    @Test
    @DisplayName("키가 달라도 같은 레포에 진행 중 Job 이 있으면 그 Job 을 돌려준다")
    void runningJobWinsOverNewKey() throws Exception {
        String first = jobIdOf(analyze(MY_REPO, KEY_1));
        String second = jobIdOf(analyze(MY_REPO, KEY_2));

        assertThat(second).isEqualTo(first);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotency-Key 없이 요청해도 Job 이 만들어진다")
    void keyHeaderIsOptional() throws Exception {

        analyze(MY_REPO, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").isString());
    }

    @Test
    @DisplayName("이미 쓴 키를 다른 레포에 다시 쓰면 409 로 거절한다")
    void sameKeyOnAnotherRepoIsRejected() throws Exception {

        analyze(MY_REPO, KEY_1).andExpect(status().isOk());

        analyze(MY_OTHER_REPO, KEY_1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_MISMATCH"));

        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 레포를 요청하면 없는 레포와 똑같이 404 다")
    void othersRepoIsNotFound() throws Exception {

        analyze(OTHERS_REPO, KEY_1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(jobCount()).isZero();
    }

    @Test
    @DisplayName("레포 id 가 UUID 형식이 아니어도 500 이 아니라 404 에러다")
    void malformedRepoIdIsNotFound() throws Exception {

        mvc.perform(post("/api/repos/{id}/analyze", "not-a-uuid")
                        .with(loggedInAs(myUserId))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("로그인하지 않은 분석 요청은 401 에러 코드로 처리된다")
    void requiresLogin() throws Exception {

        mvc.perform(post("/api/repos/{id}/analyze", MY_REPO).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        assertThat(jobCount()).isZero();
    }

    @Test
    @DisplayName("CSRF 토큰 없는 분석 요청은 403 에러 코드로 처리된다")
    void requiresCsrfToken() throws Exception {

        mvc.perform(post("/api/repos/{id}/analyze", MY_REPO).with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(jobCount()).isZero();
    }

    private RequestPostProcessor loggedInAs(Long userId) {
        LoginUser principal = new LoginUser(userId, "grow22", null);
        return authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private ResultActions analyze(UUID repoPublicId, String idempotencyKey) throws Exception {

        MockHttpServletRequestBuilder request = post("/api/repos/{id}/analyze", repoPublicId)
                .with(loggedInAs(myUserId))
                .with(csrf());

        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private String jobIdOf(ResultActions actions) throws Exception {
        return JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.data.jobId");
    }

    private Integer jobCount() {
        return jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class);
    }

    private Long insertUser(Long githubUserId, String login) {
        return jdbc.queryForObject(
                "INSERT INTO users (github_user_id, github_login) VALUES (?, ?) RETURNING id",
                Long.class, githubUserId, login);
    }

    private Long insertRepository(Long githubRepoId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO repository (github_repo_id, owner_login, name, visibility) VALUES (?, 'grow22', ?, 'PUBLIC') RETURNING id",
                Long.class, githubRepoId, name);
    }

    private void connect(UUID publicId, Long userId, Long repositoryId) {
        jdbc.update("INSERT INTO user_repository (public_id, user_id, repository_id) VALUES (?, ?, ?)",
                publicId, userId, repositoryId);
    }
}
