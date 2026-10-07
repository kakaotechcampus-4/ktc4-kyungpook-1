package com.gitory.backend.ingest.api;

import com.gitory.backend.consent.domain.LoginUser;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
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
class CommitSearchApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant MONDAY = Instant.parse("2026-09-28T09:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long repositoryId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(100L, "grow22", "gitory");
        fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);

    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void requiresLogin() throws Exception {

        mvc.perform(get("/api/repos/{id}/commits", MY_REPO).param("q", "로그인"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    @Test
    @DisplayName("남의 저장소·없는 저장소·UUID 가 아닌 id 는 모두 404 NOT_FOUND 다")
    void unknownRepositoryIsNotFound() throws Exception {

        Long otherUserId = fixtures.insertUser(2L, "taehun0208");
        UUID othersRepo = UUID.randomUUID();
        fixtures.insertUserRepository(othersRepo, otherUserId, repositoryId);

        for (String id : List.of(othersRepo.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            mvc.perform(get("/api/repos/{id}/commits", id).param("q", "로그인").with(loggedIn()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }

    }

    @Test
    @DisplayName("커밋 메시지에 검색어가 들어 있으면 대소문자와 상관없이 찾는다")
    void findsByMessageIgnoringCase() throws Exception {

        commit("a1", "grow22", "feat: Login 버튼 추가", MONDAY, null);
        commit("b2", "grow22", "docs: README 수정", MONDAY, null);

        search("login")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].message").value("feat: Login 버튼 추가"));

    }

    @Test
    @DisplayName("커밋 번호가 검색어로 시작하면 찾고, 중간에만 들어 있으면 찾지 않는다")
    void findsByShaPrefix() throws Exception {

        commit("a1b2c3", "grow22", "fix: 세션 버그", MONDAY, null);
        commit("ffa1b2c3", "grow22", "fix: 다른 버그", MONDAY, null);

        search("A1B2C3")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sha").value(sha("a1b2c3")));

    }

    @Test
    @DisplayName("내 커밋만 찾는다 — 남의 커밋·봇·머지·lock 파일만 바꾼 커밋은 빼고, 남이 먼저 분석해 NOT_OWN 이 붙은 내 커밋은 찾는다")
    void findsOnlyMyCommits() throws Exception {

        commit("01", "grow22", "feat: 캐시 도입", MONDAY, null);
        commit("02", "Grow22", "feat: 캐시 만료 추가", MONDAY, "NOT_OWN");
        commit("03", "taehun0208", "feat: 캐시 설정", MONDAY, "NOT_OWN");
        commit("04", "grow22", "Merge: 캐시 브랜치", MONDAY, "MERGE_COMMIT");
        commit("05", "grow22", "chore: 캐시 lock 갱신", MONDAY, "LOCKFILE_ONLY");
        commit("06", "dependabot[bot]", "build: 캐시 라이브러리 올림", MONDAY, "BOT");
        commit("07", null, "feat: 캐시 실험", MONDAY, null);
        Long otherRepository = fixtures.insertRepository(200L, "grow22", "other");
        jdbc.update("INSERT INTO git_commit (repository_id, sha, author_login, message, authored_at) VALUES (?, ?, ?, ?, ?)",
                otherRepository, sha("08"), "grow22", "feat: 캐시 다른 저장소", Timestamp.from(MONDAY));

        search("캐시")
                .andExpect(jsonPath("$.data[*].message", containsInAnyOrder("feat: 캐시 도입", "feat: 캐시 만료 추가")));

    }

    @Test
    @DisplayName("최근 커밋부터 최대 20개만 돌려준다")
    void returnsNewestTwenty() throws Exception {

        for (int i = 1; i <= 25; i++) {
            commit(String.format("%02d", i), "grow22", "feat: 작업 " + i, MONDAY.plusSeconds(i * 60L), null);
        }

        search("작업")
                .andExpect(jsonPath("$.data.length()").value(20))
                .andExpect(jsonPath("$.data[0].message").value("feat: 작업 25"))
                .andExpect(jsonPath("$.data[19].message").value("feat: 작업 6"));

    }

    @Test
    @DisplayName("응답의 url 은 GitHub 커밋 주소이고, 파일 경로와 후보 id 는 아직 없어 null 이다")
    void fillsCommitFields() throws Exception {

        commit("a1", "grow22", "feat: 로그인 추가", MONDAY, null);

        search("로그인")
                .andExpect(jsonPath("$.data[0].sha").value(sha("a1")))
                .andExpect(jsonPath("$.data[0].at").value("2026-09-28T09:00:00Z"))
                .andExpect(jsonPath("$.data[0].url").value("https://github.com/grow22/gitory/commit/" + sha("a1")))
                .andExpect(jsonPath("$.data[0].path").value(nullValue()))
                .andExpect(jsonPath("$.data[0].candidateId").value(nullValue()));

    }

    @Test
    @DisplayName("분석 전이라 수집한 커밋이 없으면 빈 배열이다")
    void returnsEmptyBeforeAnalysis() throws Exception {

        search("로그인")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

    }

    @Test
    @DisplayName("검색어의 % 와 _ 는 아무 글자가 아니라 그 글자 그대로 찾는다")
    void treatsWildcardsAsLetters() throws Exception {

        commit("01", "grow22", "perf: 응답 50% 단축", MONDAY, null);
        commit("02", "grow22", "refactor: snake_case 정리", MONDAY, null);
        commit("03", "grow22", "feat: 검색 추가", MONDAY, null);

        search("%").andExpect(jsonPath("$.data[*].message", contains("perf: 응답 50% 단축")));
        search("_").andExpect(jsonPath("$.data[*].message", contains("refactor: snake_case 정리")));

    }

    private void commit(String shaPrefix, String authorLogin, String message, Instant authoredAt, String exclusionReason) {

        jdbc.update("""
                INSERT INTO git_commit (repository_id, sha, author_login, message, authored_at, is_excluded, exclusion_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, repositoryId, sha(shaPrefix), authorLogin, message, Timestamp.from(authoredAt),
                exclusionReason != null, exclusionReason);

    }

    private static String sha(String prefix) {

        return (prefix + "0".repeat(40)).substring(0, 40);

    }

    private ResultActions search(String query) throws Exception {

        return mvc.perform(get("/api/repos/{id}/commits", MY_REPO).param("q", query).with(loggedIn()));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
