package com.gitory.backend.audit;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.domain.StubGithubConfig;
import com.gitory.backend.job.domain.AnalysisJob;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.support.TestBrowser;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Import(StubGithubConfig.class)
@Testcontainers
class AuditTrailTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AnalysisJobRepository jobs;

    private Long myUserId;
    private Long myRepoId;
    private Long othersUserId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");

        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, repositoryId);

    }

    @Test
    @DisplayName("첫 로그인으로 GitHub 연결이 저장되면 그 연결로 CONNECT 성공 기록이 남는다")
    void recordsConnectOnFirstLogin() throws Exception {

        login(new TestBrowser(mvc));

        Long userId = loggedInUserId();
        assertThat(events()).containsExactly(new AuditRow(userId, "CONNECT", connectionIdOf(userId), "OK"));

    }

    @Test
    @DisplayName("다시 로그인해 연결이 갱신돼도 같은 연결로 CONNECT 성공 기록이 하나 더 남는다")
    void recordsConnectOnRelogin() throws Exception {

        TestBrowser browser = new TestBrowser(mvc);
        login(browser);
        browser.clearCookies();
        login(browser);

        Long userId = loggedInUserId();
        AuditRow connect = new AuditRow(userId, "CONNECT", connectionIdOf(userId), "OK");
        assertThat(events()).containsExactly(connect, connect);

    }

    @Test
    @DisplayName("로그인 콜백 요청에서 남긴 연결 기록에도 서버가 만든 요청 id 가 같이 남는다")
    void recordsRequestIdOfLoginCallback() throws Exception {

        login(new TestBrowser(mvc));

        List<String> requestIds = jdbc.queryForList("SELECT request_id FROM audit_event", String.class);
        assertThat(requestIds).hasSize(1);
        assertThatCode(() -> UUID.fromString(requestIds.get(0))).doesNotThrowAnyException();

    }

    @Test
    @DisplayName("분석 접수 기록과 거절 기록에는 요청에 실린 X-Request-Id 대신 서버가 요청마다 만든 id 가 남는다")
    void recordsRequestIdOfAnalyzeAndDenied() throws Exception {

        AnalysisJob othersJob = jobs.save(AnalysisJob.enqueue(othersUserId, othersRepoId, KEY));

        mvc.perform(post("/api/repos/{id}/analyze", MY_REPO).with(loggedInAs(myUserId)).with(csrf())
                        .header("X-Request-Id", "client-chosen-1"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs/{id}", othersJob.getPublicId()).with(loggedInAs(myUserId))
                        .header("X-Request-Id", "client-chosen-2"))
                .andExpect(status().isNotFound());

        List<String> requestIds = jdbc.queryForList("SELECT request_id FROM audit_event ORDER BY id", String.class);
        assertThat(requestIds).hasSize(2).doesNotHaveDuplicates();
        requestIds.forEach(id -> assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException());

    }

    @Test
    @DisplayName("분석 요청으로 새 Job 이 접수되면 그 Job 으로 ANALYZE 성공 기록이 남는다")
    void recordsAnalyzeForNewJob() throws Exception {

        analyze(MY_REPO).andExpect(status().isOk());

        Long jobId = jdbc.queryForObject("SELECT id FROM analysis_job", Long.class);
        assertThat(events()).containsExactly(new AuditRow(myUserId, "ANALYZE", jobId, "OK"));

    }

    @Test
    @DisplayName("진행 중인 기존 Job 을 돌려준 분석 요청은 기록하지 않는다")
    void doesNotRecordReturnedExistingJob() throws Exception {

        analyze(MY_REPO).andExpect(status().isOk());
        analyze(MY_REPO).andExpect(status().isOk());

        assertThat(events()).hasSize(1);

    }

    @Test
    @DisplayName("남의 저장소로 분석을 요청하면 404 이고 그 저장소 연결로 ANALYZE 거절 기록이 남는다")
    void recordsDeniedAnalyzeOfOthersRepository() throws Exception {

        analyze(OTHERS_REPO).andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "ANALYZE", othersRepoId, "DENIED"));

    }

    @Test
    @DisplayName("남의 Job 을 조회하면 404 이고 그 Job 으로 VIEW_JOB 거절 기록이 남는다")
    void recordsDeniedViewOfOthersJob() throws Exception {

        AnalysisJob othersJob = jobs.save(AnalysisJob.enqueue(othersUserId, othersRepoId, KEY));

        mvc.perform(get("/api/jobs/{id}", othersJob.getPublicId()).with(loggedInAs(myUserId)))
                .andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_JOB", othersJob.getId(), "DENIED"));

    }

    @Test
    @DisplayName("남의 Job 을 취소하면 404 이고 그 Job 으로 CANCEL_JOB 거절 기록이 남는다")
    void recordsDeniedCancelOfOthersJob() throws Exception {

        AnalysisJob othersJob = jobs.save(AnalysisJob.enqueue(othersUserId, othersRepoId, KEY));

        mvc.perform(post("/api/jobs/{id}/cancel", othersJob.getPublicId()).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CANCEL_JOB", othersJob.getId(), "DENIED"));

    }

    @Test
    @DisplayName("내 Job 을 조회하고 취소하면 200 이고 아무것도 기록하지 않는다")
    void doesNotRecordMyOwnJob() throws Exception {

        AnalysisJob myJob = jobs.save(AnalysisJob.enqueue(myUserId, myRepoId, KEY));

        mvc.perform(get("/api/jobs/{id}", myJob.getPublicId()).with(loggedInAs(myUserId)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/jobs/{id}/cancel", myJob.getPublicId()).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("없는 저장소나 Job 을 가리키면 404 이고 아무것도 기록하지 않는다")
    void doesNotRecordUnknownIds() throws Exception {

        analyze(UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}", UUID.randomUUID()).with(loggedInAs(myUserId)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/jobs/{id}/cancel", UUID.randomUUID()).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(events()).isEmpty();

    }

    private void login(TestBrowser browser) throws Exception {

        String location = browser.perform(get("/api/auth/github/start"))
                .andReturn().getResponse().getRedirectedUrl();

        browser.perform(get("/api/auth/github/callback")
                        .param("code", "stub-authorization-code")
                        .param("state", TestBrowser.queryOf(location).get("state")))
                .andExpect(redirectedUrl("/"));

    }

    private ResultActions analyze(UUID repoPublicId) throws Exception {

        return mvc.perform(post("/api/repos/{id}/analyze", repoPublicId).with(loggedInAs(myUserId)).with(csrf()));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private Long loggedInUserId() {

        return jdbc.queryForObject("SELECT id FROM users WHERE github_user_id = ?", Long.class,
                StubGithubConfig.GITHUB_USER_ID);

    }

    private Long connectionIdOf(Long userId) {

        return jdbc.queryForObject("SELECT id FROM github_connection WHERE user_id = ?", Long.class, userId);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
