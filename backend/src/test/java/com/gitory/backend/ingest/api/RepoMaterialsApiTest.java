package com.gitory.backend.ingest.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.support.TestFixtures;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
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
class RepoMaterialsApiTest {

    private static final Instant MONDAY = Instant.parse("2026-09-28T09:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubCollectionAccessPort github;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long repositoryId;
    private UUID repoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(100L, "kakao", "gitory");
        jdbc.update("UPDATE repository SET default_branch = 'develop' WHERE id = ?", repositoryId);
        repoId = UUID.randomUUID();
        fixtures.insertUserRepository(repoId, myUserId, repositoryId);

    }

    @Test
    @DisplayName("분석에 쓴 내 커밋과 뺀 내 커밋을 뺀 이유와 함께 최근 순으로 주고, 남의 커밋은 주지 않으며 GitHub 을 부르지 않는다")
    void listsMyUsedAndExcludedCommitsNewestFirst() throws Exception {

        commit("a1", "Grow22", "feat: 세션 인덱스 추가\n\n본문", MONDAY, null, 3, null);
        commit("b2", "grow22", "Merge branch 'develop'", MONDAY.plusSeconds(60), "MERGE_COMMIT", 0, null);
        commit("c3", "taehun0208", "fix: 팀원 커밋", MONDAY.plusSeconds(120), null, 1, null);
        commit("f6", "taehun0208", "Merge branch 'main'", MONDAY.plusSeconds(180), "MERGE_COMMIT", 0, null);

        Map<String, Object> first = JsonPath.read(bodyOf(materials(repoId)), "$.data.commits[0]");

        assertThat(first).containsOnlyKeys("sha", "title", "authoredAt", "files", "pullRequestNumber", "excluded");
        materials(repoId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commits[*].sha", contains("b200000", "a100000")))
                .andExpect(jsonPath("$.data.commits[*].title", contains("Merge branch 'develop'", "feat: 세션 인덱스 추가")))
                .andExpect(jsonPath("$.data.commits[0].excluded").value("MERGE_COMMIT"))
                .andExpect(jsonPath("$.data.commits[1].excluded").value(nullValue()))
                .andExpect(jsonPath("$.data.commits[1].files").value(3))
                .andExpect(jsonPath("$.data.commits[1].authoredAt").value("2026-09-28T09:00:00Z"));
        verifyNoInteractions(github);

    }

    @Test
    @DisplayName("커밋은 분석과 같은 규칙으로 PR 에 묶고, PR 은 내 커밋이 묶인 것만 번호가 큰 순으로 묶인 커밋 수와 함께 준다")
    void bindsCommitsToPullRequestsLikeTheAnalysis() throws Exception {

        commit("a1", "grow22", "feat: 기능", MONDAY, null, 1, null);
        commit("a2", "grow22", "feat: 기능 마무리", MONDAY.plusSeconds(30), null, 1, null);
        commit("d4", "grow22", "feat: 스쿼시 (#18)", MONDAY.plusSeconds(60), null, 1, "{18}");
        commit("c3", "taehun0208", "fix: 팀원 커밋", MONDAY.plusSeconds(120), null, 1, null);
        pullRequest(17, "grow22", "feature/17", MONDAY, sha("a1"), sha("a2"));
        pullRequest(30, "taehun0208", "feature/30", MONDAY, sha("a1"), sha("a2"), sha("c3"), sha("e5"));
        pullRequest(18, "grow22", "feature/18", null);
        pullRequest(25, "taehun0208", "feature/25", MONDAY, sha("c3"));

        materials(repoId)
                .andExpect(jsonPath("$.data.commits[*].pullRequestNumber", contains(18, 17, 17)))
                .andExpect(jsonPath("$.data.pullRequests[*].number", contains(18, 17)))
                .andExpect(jsonPath("$.data.pullRequests[*].commitCount", contains(1, 2)))
                .andExpect(jsonPath("$.data.pullRequests[0].mergedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.pullRequests[1].mergedAt").value("2026-09-28T09:00:00Z"))
                .andExpect(jsonPath("$.data.pullRequests[1].title").value("PR 17 제목"));

    }

    @Test
    @DisplayName("뺀 커밋은 PR 에 들어 있어도 PR 에 묶지 않고 PR 의 커밋 수에도 세지 않는다")
    void excludedCommitsStayOutOfPullRequests() throws Exception {

        commit("a1", "grow22", "feat: 기능", MONDAY, null, 1, null);
        commit("b2", "grow22", "Merge branch 'develop'", MONDAY.plusSeconds(60), "MERGE_COMMIT", 0, null);
        pullRequest(17, "grow22", "feature/17", MONDAY, sha("a1"), sha("b2"));

        materials(repoId)
                .andExpect(jsonPath("$.data.commits[0].pullRequestNumber").value(nullValue()))
                .andExpect(jsonPath("$.data.commits[1].pullRequestNumber").value(17))
                .andExpect(jsonPath("$.data.pullRequests[0].commitCount").value(1));

    }

    @Test
    @DisplayName("분석 전이라 저장된 커밋이 없으면 커밋·PR 모두 빈 배열이다")
    void beforeAnalysisListsNothing() throws Exception {

        materials(repoId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commits.length()").value(0))
                .andExpect(jsonPath("$.data.pullRequests.length()").value(0));

    }

    @Test
    @DisplayName("남의 저장소·없는 저장소·UUID 가 아닌 id 는 모두 404 NOT_FOUND 이고, 남의 저장소만 거절 기록 한 줄을 남긴다")
    void othersOrUnknownRepositoryIsNotFound() throws Exception {

        Long otherUserId = fixtures.insertUser(2L, "taehun0208");
        UUID othersId = UUID.randomUUID();
        Long othersConnection = fixtures.insertUserRepository(othersId, otherUserId, repositoryId);

        for (String id : List.of(othersId.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            mvc.perform(get("/api/repos/{id}/materials", id).with(loggedIn()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }

        assertThat(auditRows()).containsExactly(myUserId + " VIEW_REPO_MATERIALS " + othersConnection + " DENIED");

    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void requiresLogin() throws Exception {

        mvc.perform(get("/api/repos/{id}/materials", repoId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    private void commit(String shaPrefix, String login, String message, Instant authoredAt, String exclusionReason,
                        int changedFiles, String issueNumbers) {

        jdbc.update("""
                INSERT INTO git_commit (repository_id, sha, author_login, message, authored_at, changed_files,
                                        is_excluded, exclusion_reason, issue_numbers)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::int[])
                """, repositoryId, sha(shaPrefix), login, message, Timestamp.from(authoredAt), changedFiles,
                exclusionReason != null, exclusionReason, issueNumbers);

    }

    private void pullRequest(int number, String author, String head, Instant mergedAt, String... shas) {

        jdbc.update("""
                INSERT INTO pull_request (repository_id, github_pr_number, title, state, author_login,
                                          base_branch, head_branch, opened_at, merged_at, commit_shas)
                VALUES (?, ?, ?, 'CLOSED', ?, 'develop', ?, ?, ?, ?::text[])
                """, repositoryId, number, "PR " + number + " 제목", author, head, Timestamp.from(MONDAY),
                mergedAt == null ? null : Timestamp.from(mergedAt), "{" + String.join(",", shas) + "}");

    }

    private static String sha(String prefix) {

        return (prefix + "0".repeat(40)).substring(0, 40);

    }

    private ResultActions materials(UUID id) throws Exception {

        return mvc.perform(get("/api/repos/{id}/materials", id).with(loggedIn()));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private String bodyOf(ResultActions actions) throws Exception {

        return actions.andReturn().getResponse().getContentAsString();

    }

    private List<String> auditRows() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> row.getLong("user_id") + " " + row.getString("action") + " "
                        + row.getLong("subject_id") + " " + row.getString("outcome"));

    }
}
