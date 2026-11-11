package com.gitory.backend.consent.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.infra.GithubGrantClient;
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
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
@SuppressWarnings({"rawtypes", "unchecked"})
class AccountDeletionApiTest {

    private static final String TOKEN = "gho_StubbedAccessTokenExample1234567";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FindByIndexNameSessionRepository sessions;

    @MockitoBean
    GithubGrantClient grants;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.update("DELETE FROM spring_session");
        myUserId = fixtures.insertUser(1L, "grow22");
        otherUserId = fixtures.insertUser(2L, "other");

    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void requiresLogin() throws Exception {

        mvc.perform(delete("/api/me").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    @Test
    @DisplayName("탈퇴하면 사용자와 카드·프로필·자소서·GitHub 연결이 지워지고 200 에 data 와 error 는 null 이다")
    void deletesUserAndTheirData() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), myUserId);
        jdbc.update("INSERT INTO user_profile (user_id, saved_at) VALUES (?, now())", myUserId);
        insertCoverLetter(myUserId, "DRAFTED");
        fixtures.insertGithubConnection(myUserId, TOKEN);

        leave()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(countWhere("users", "id", myUserId)).isZero();
        assertThat(countWhere("card", "user_id", myUserId)).isZero();
        assertThat(countWhere("user_profile", "user_id", myUserId)).isZero();
        assertThat(countWhere("cover_letter", "user_id", myUserId)).isZero();
        assertThat(countWhere("github_connection", "user_id", myUserId)).isZero();

    }

    @Test
    @DisplayName("대기 중인 분석 Job 이 있으면 409 ACTIVE_WORK_RUNNING 이고 아무것도 지우지 않는다")
    void refusesWhileAnalysisIsQueued() throws Exception {

        insertJob(myUserId, "QUEUED");

        assertRefusedAndNothingRemoved();

    }

    @Test
    @DisplayName("도는 중인 분석 Job 이 있으면 409 ACTIVE_WORK_RUNNING 이고 아무것도 지우지 않는다")
    void refusesWhileAnalysisIsRunning() throws Exception {

        insertJob(myUserId, "RUNNING");

        assertRefusedAndNothingRemoved();

    }

    @Test
    @DisplayName("도는 중인 도우미 실행이 있으면 409 ACTIVE_WORK_RUNNING 이고 아무것도 지우지 않는다")
    void refusesWhileAssistantIsRunning() throws Exception {

        insertAssistantRun(myUserId, "RUNNING");

        assertRefusedAndNothingRemoved();

    }

    @Test
    @DisplayName("AI 단계가 도는 자소서가 있으면 409 ACTIVE_WORK_RUNNING 이고 아무것도 지우지 않는다")
    void refusesWhileCoverLetterAiStepIsRunning() throws Exception {

        insertCoverLetter(myUserId, "DRAFTING");

        assertRefusedAndNothingRemoved();

    }

    @Test
    @DisplayName("사용자 답을 기다리는 도우미 실행과 끝난 Job 은 탈퇴를 막지 않는다")
    void waitingOrFinishedWorkDoesNotBlock() throws Exception {

        insertAssistantRun(myUserId, "WAITING_USER");
        insertJob(myUserId, "SUCCEEDED");

        leave().andExpect(status().isOk());

        assertThat(countWhere("users", "id", myUserId)).isZero();

    }

    @Test
    @DisplayName("다른 사용자가 쓰지 않는 저장소는 원본 기록과 함께 지워진다")
    void removesRepositoryNobodyElseUses() throws Exception {

        Long repositoryId = linkRepository(100L, myUserId);
        fixtures.insertCommit(repositoryId, "sha-mine");

        leave().andExpect(status().isOk());

        assertThat(countWhere("repository", "id", repositoryId)).isZero();
        assertThat(countWhere("git_commit", "repository_id", repositoryId)).isZero();

    }

    @Test
    @DisplayName("다른 사용자가 아직 연결한 저장소는 원본 기록과 함께 남는다")
    void keepsRepositoryAnotherUserStillLinks() throws Exception {

        Long repositoryId = linkRepository(100L, myUserId);
        fixtures.insertUserRepository(otherUserId, repositoryId);
        fixtures.insertCommit(repositoryId, "sha-shared");

        leave().andExpect(status().isOk());

        assertThat(countWhere("repository", "id", repositoryId)).isEqualTo(1);
        assertThat(countWhere("git_commit", "repository_id", repositoryId)).isEqualTo(1);
        assertThat(countWhere("user_repository", "user_id", otherUserId)).isEqualTo(1);

    }

    @Test
    @DisplayName("다른 사용자의 데이터는 그대로다")
    void leavesOtherUsersDataUntouched() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), otherUserId);
        fixtures.insertGithubConnection(otherUserId, TOKEN);
        jdbc.update("INSERT INTO user_profile (user_id, saved_at) VALUES (?, now())", otherUserId);
        linkRepository(200L, otherUserId);

        leave().andExpect(status().isOk());

        assertThat(countWhere("users", "id", otherUserId)).isEqualTo(1);
        assertThat(countWhere("card", "user_id", otherUserId)).isEqualTo(1);
        assertThat(countWhere("github_connection", "user_id", otherUserId)).isEqualTo(1);
        assertThat(countWhere("user_profile", "user_id", otherUserId)).isEqualTo(1);
        assertThat(countWhere("user_repository", "user_id", otherUserId)).isEqualTo(1);

    }

    @Test
    @DisplayName("내 로그인 세션은 모두 지워지고 다른 사용자의 세션은 남는다")
    void removesAllMySessionsOnly() throws Exception {

        saveSession(myUserId);
        saveSession(myUserId);
        saveSession(otherUserId);

        leave().andExpect(status().isOk());

        assertThat(sessionCount(myUserId)).isZero();
        assertThat(sessionCount(otherUserId)).isEqualTo(1);

    }

    @Test
    @DisplayName("GitHub 앱 권한 삭제가 실패해도 탈퇴는 끝까지 진행된다")
    void deletesEvenWhenGithubGrantFails() throws Exception {

        fixtures.insertGithubConnection(myUserId, TOKEN);
        willThrow(new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY)).given(grants).deleteGrant(anyString());

        leave().andExpect(status().isOk());

        assertThat(countWhere("users", "id", myUserId)).isZero();

    }

    @Test
    @DisplayName("탈퇴 감사 기록은 user_id 가 비어 남고 지운 사용자 id 를 subject_id 에 가진다")
    void keepsAuditRecordWithoutUserId() throws Exception {

        leave().andExpect(status().isOk());

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT user_id, subject_id, outcome FROM audit_event WHERE action = 'DELETE_ACCOUNT'");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("user_id")).isNull();
        assertThat(events.getFirst().get("subject_id")).isEqualTo(myUserId);
        assertThat(events.getFirst().get("outcome")).isEqualTo("OK");

    }

    private void assertRefusedAndNothingRemoved() throws Exception {

        fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertGithubConnection(myUserId, TOKEN);
        linkRepository(100L, myUserId);
        saveSession(myUserId);
        int linkedBefore = countWhere("user_repository", "user_id", myUserId);

        leave()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_WORK_RUNNING"));

        assertThat(countWhere("users", "id", myUserId)).isEqualTo(1);
        assertThat(countWhere("card", "user_id", myUserId)).isEqualTo(1);
        assertThat(countWhere("user_repository", "user_id", myUserId)).isEqualTo(linkedBefore);
        assertThat(sessionCount(myUserId)).isEqualTo(1);
        Map<String, Object> connection = jdbc.queryForMap(
                "SELECT token_enc, revoked_at FROM github_connection WHERE user_id = ?", myUserId);
        assertThat(connection.get("token_enc")).isNotNull();
        assertThat(connection.get("revoked_at")).isNull();
        verifyNoInteractions(grants);

    }

    private Long linkRepository(Long githubRepoId, Long userId) {

        Long repositoryId = fixtures.insertRepository(githubRepoId, "owner", "repo-" + githubRepoId);
        fixtures.insertUserRepository(userId, repositoryId);
        return repositoryId;

    }

    private void insertCoverLetter(Long userId, String stage) {

        jdbc.update("""
                INSERT INTO cover_letter (public_id, user_id, job_role, stage, running_since, created_at, updated_at)
                VALUES (?, ?, '백엔드', ?, NULL, now(), now())
                """, UUID.randomUUID(), userId, stage);

    }

    private void insertAssistantRun(Long userId, String state) {

        jdbc.update("""
                INSERT INTO assistant_run (public_id, user_id, state, step_limit, created_at, updated_at)
                VALUES (?, ?, ?, 5, now(), now())
                """, UUID.randomUUID(), userId, state);

    }

    private void insertJob(Long userId, String state) {

        Long repositoryId = fixtures.insertRepository(900L + userId, "owner", "job-repo");
        Long userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        jdbc.update("INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, state) VALUES (?, ?, ?, ?)",
                userId, userRepositoryId, UUID.randomUUID().toString(), state);

    }

    private void saveSession(Long userId) {

        Session session = sessions.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, String.valueOf(userId));
        sessions.save(session);

    }

    private int sessionCount(Long userId) {

        return jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class,
                String.valueOf(userId));

    }

    private int countWhere(String table, String column, Long value) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);

    }

    private ResultActions leave() throws Exception {

        return mvc.perform(delete("/api/me").with(loggedIn()).with(csrf()));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
