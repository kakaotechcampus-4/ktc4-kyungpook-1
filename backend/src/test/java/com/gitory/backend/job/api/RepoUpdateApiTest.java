package com.gitory.backend.job.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
class RepoUpdateApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubCollectionAccessPort githubAccess;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long myRepoId;
    private Long othersUserId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, fixtures.insertRepository(1L, "grow22", "gitory"));
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId,
                fixtures.insertRepository(2L, "someone-else", "blog"));

    }

    @Test
    @DisplayName("새 활동을 확인하면 정리한 뒤 push 가 있는 레포의 분석 Job 을 접수하고 접수한 레포 수를 queued 로 돌려준다")
    void checkQueuesStaleRepositoryAndAnswersQueuedCount() throws Exception {

        jdbc.update("UPDATE repository SET github_pushed_at = now() WHERE github_repo_id = 1");
        jdbc.update("UPDATE user_repository SET last_analyzed_at = now() - interval '1 day' WHERE id = ?", myRepoId);

        mvc.perform(post("/api/repo-updates/check").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.queued").value(1));
        mvc.perform(post("/api/repo-updates/check").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queued").value(0));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job WHERE type = 'ANALYZE'", Integer.class))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("알림 목록은 레포 id·owner/name·카드 수·끝난 시각을 담고, 본 것으로 닫으면 다시 나오지 않는다")
    void listsUpdatesAndSeenClosesThem() throws Exception {

        insertReady(myUserId, myRepoId, 3, Instant.parse("2026-11-05T00:00:00Z"));

        mvc.perform(get("/api/repo-updates").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].repoId").value(MY_REPO.toString()))
                .andExpect(jsonPath("$.data[0].repoName").value("grow22/gitory"))
                .andExpect(jsonPath("$.data[0].cardCount").value(3))
                .andExpect(jsonPath("$.data[0].finishedAt").value("2026-11-05T00:00:00Z"));

        mvc.perform(post("/api/repo-updates/" + MY_REPO + "/seen").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
        mvc.perform(get("/api/repo-updates").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

    }

    @Test
    @DisplayName("남의 레포와 UUID 가 아닌 id 의 알림을 닫으려 하면 404 이고 남의 알림은 닫히지 않는다")
    void seenHidesOthersRepositories() throws Exception {

        insertReady(othersUserId, othersRepoId, 2, Instant.parse("2026-11-05T00:00:00Z"));

        mvc.perform(post("/api/repo-updates/" + OTHERS_REPO + "/seen").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(post("/api/repo-updates/not-a-uuid/seen").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM repo_refresh WHERE seen_at IS NULL", Integer.class))
                .isEqualTo(1);

    }

    private void insertReady(Long ownerId, Long userRepositoryId, int cardCount, Instant finishedAt) {

        Long analysisJobId = jdbc.queryForObject(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, 'ANALYZE', 'SUCCEEDED') RETURNING id",
                Long.class, ownerId, userRepositoryId, UUID.randomUUID().toString());
        Long draftJobId = jdbc.queryForObject(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, 'DRAFT', 'SUCCEEDED') RETURNING id",
                Long.class, ownerId, userRepositoryId, UUID.randomUUID().toString());
        Timestamp at = Timestamp.from(finishedAt);
        jdbc.update("""
                INSERT INTO repo_refresh (user_id, user_repository_id, analysis_job_id, draft_job_id, previous_analyzed_at,
                    card_count, state, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, 'READY', ?, ?)
                """, ownerId, userRepositoryId, analysisJobId, draftJobId, at, cardCount, at, at);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
