package com.gitory.backend.recommend.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.BoardVerdictFixtures;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
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
class CandidateBoardVerdictApiTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TEAMMATE_REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant ANALYZED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant COUNTED_AT = Instant.parse("2026-10-02T00:00:00Z");
    private static final String NEVER_ANALYZED = "아직 정리하지 않은 저장소예요";
    private static final String NOT_GROUPED_YET = "아직 이 저장소의 후보를 만들지 못했어요";
    private static final String UNCOUNTED = "내 커밋 수를 아직 세지 못했어요";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private CandidateBoardFixtures board;
    private BoardVerdictFixtures verdict;
    private Long myUserId;
    private Long repositoryId;
    private Long myRepoId;
    private Long teammateRepoId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        board = new CandidateBoardFixtures(jdbc);
        verdict = new BoardVerdictFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        Long teammateUserId = fixtures.insertUser(2L, "teammate");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        teammateRepoId = fixtures.insertUserRepository(TEAMMATE_REPO, teammateUserId, repositoryId);

    }

    @Test
    @DisplayName("후보가 있고 마지막 수집이 부분 수집이면 PARTIAL 이고 partial 이 true 다")
    void marksCandidatesOfPartialRunAsPartial() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        verdict.insertRun(myRepoId, "GITHUB_RATE_LIMITED", 10, 2, Instant.now());

        board()
                .andExpect(jsonPath("$.data.verdict").value("PARTIAL"))
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.candidates", hasSize(1)));

    }

    @Test
    @DisplayName("후보가 0개면 부분 수집이어도 EMPTY 이고 partial 은 true 다")
    void keepsEmptyVerdictButReportsPartial() throws Exception {

        verdict.insertRun(myRepoId, "CAP_EXCEEDED", 1000, 50, Instant.now());
        verdict.setCounts(myRepoId, COUNTED_AT, 1500, 10, 65, 3, 2);
        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT.plusSeconds(1));

        board()
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.readCoverage.commitsTotal").value(1500));

    }

    @Test
    @DisplayName("수집 기록이 없으면 partial 은 false 다")
    void isNotPartialWithoutAnyRun() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");

        board()
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.partial").value(false));

    }

    @Test
    @DisplayName("옛 부분 수집 뒤에 끝까지 읽은 수집이 있으면 partial 은 false 다")
    void followsNewerFullRunOverOlderPartialRun() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        verdict.insertRun(myRepoId, "GITHUB_RATE_LIMITED", 10, 2, Instant.parse("2026-10-01T00:00:00Z"));
        verdict.insertRun(myRepoId, null, 300, 20, Instant.parse("2026-10-02T00:00:00Z"));

        board()
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.partial").value(false));

    }

    @Test
    @DisplayName("옛 전체 수집 뒤에 부분 수집이 있으면 partial 은 true 다")
    void followsNewerPartialRunOverOlderFullRun() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        verdict.insertRun(myRepoId, null, 300, 20, Instant.parse("2026-10-01T00:00:00Z"));
        verdict.insertRun(myRepoId, "GITHUB_RATE_LIMITED", 10, 2, Instant.parse("2026-10-02T00:00:00Z"));

        board()
                .andExpect(jsonPath("$.data.verdict").value("PARTIAL"))
                .andExpect(jsonPath("$.data.partial").value(true));

    }

    @Test
    @DisplayName("같은 GitHub 저장소를 연결한 팀원의 부분 수집은 내 partial 에 영향을 주지 않는다")
    void ignoresPartialRunOfTeammateOnTheSameGithubRepository() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        verdict.insertRun(teammateRepoId, "GITHUB_RATE_LIMITED", 10, 2, Instant.now());

        board()
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.partial").value(false));

    }

    @Test
    @DisplayName("GitHub 한도로 일부만 읽어도 다시 시도할 때까지 기다릴 초는 null 로 키를 남긴다")
    void keepsRetryAfterSecondsNullForRateLimitedRun() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        verdict.insertRun(myRepoId, "GITHUB_RATE_LIMITED", 10, 2, Instant.now());

        Map<String, Object> data = JsonPath.read(body(board()), "$.data");

        assertThat(data).containsKey("retryAfterSeconds");
        assertThat(data.get("retryAfterSeconds")).isNull();

    }

    @Test
    @DisplayName("부분 수집이 아니면 readCoverage 는 null 이다")
    void hasNoReadCoverageWhenNotPartial() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        verdict.insertRun(myRepoId, null, 300, 20, Instant.now());
        verdict.setCounts(myRepoId, COUNTED_AT, 300, 10, 20, 3, 2);

        board().andExpect(jsonPath("$.data.readCoverage").value(nullValue()));

    }

    @Test
    @DisplayName("부분 수집이어도 전체 개수를 센 적이 없으면 readCoverage 는 null 이다")
    void hasNoReadCoverageWhenNeverCounted() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        verdict.insertRun(myRepoId, "CAP_EXCEEDED", 1000, 50, Instant.now());

        board()
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.readCoverage").value(nullValue()));

    }

    @Test
    @DisplayName("부분 수집이고 개수를 센 적이 있으면 읽은 커밋 수는 그 저장소에 저장된 커밋 수, 읽은 PR 수는 수집 기록, 전체 수는 GitHub 에서 센 작성자 구분 없는 숫자다")
    void fillsReadCoverageFromStoredCommitsRunAndGithubTotals() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        storeCommits(3);
        verdict.insertRun(myRepoId, "CAP_EXCEEDED", 3, 50, Instant.now());
        verdict.setCounts(myRepoId, COUNTED_AT, 1500, 100, 65, 20, 5);

        board()
                .andExpect(jsonPath("$.data.readCoverage.commitsRead").value(3))
                .andExpect(jsonPath("$.data.readCoverage.commitsTotal").value(1500))
                .andExpect(jsonPath("$.data.readCoverage.prsRead").value(50))
                .andExpect(jsonPath("$.data.readCoverage.prsTotal").value(65));

    }

    @Test
    @DisplayName("다시 분석한 수집 기록에 새 커밋만 남아도 읽은 커밋 수는 그 저장소에 저장된 커밋 수다")
    void countsStoredCommitsWhenRunHasOnlyNewOnes() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        storeCommits(3);
        verdict.insertRun(myRepoId, "GITHUB_RATE_LIMITED", 1, 10, Instant.now());
        verdict.setCounts(myRepoId, COUNTED_AT, 1500, 100, 65, 20, 5);

        board().andExpect(jsonPath("$.data.readCoverage.commitsRead").value(3));

    }

    @ParameterizedTest
    @CsvSource({"2, 65, 3, 65", "1500, 40, 1500, 50"})
    @DisplayName("GitHub 에서 센 전체 수가 읽은 수보다 작으면 전체 수를 읽은 수로 올려 읽은 수가 전체 수를 넘지 않게 한다")
    void raisesTotalsToReadNumbers(int githubCommits, int githubPrs, int commitsTotal, int prsTotal) throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        storeCommits(3);
        verdict.insertRun(myRepoId, "CAP_EXCEEDED", 3, 50, Instant.now());
        verdict.setCounts(myRepoId, COUNTED_AT, githubCommits, 0, githubPrs, 0, 0);

        board()
                .andExpect(jsonPath("$.data.readCoverage.commitsTotal").value(commitsTotal))
                .andExpect(jsonPath("$.data.readCoverage.prsTotal").value(prsTotal));

    }

    @Test
    @DisplayName("한 번도 분석하지 않은 빈 보드는 계정 없는 커밋이 저장돼 있어도 한 줄뿐이다")
    void keepsNeverAnalyzedBoardToOneLine() throws Exception {

        verdict.insertCommit(repositoryId, "a".repeat(40), null, "메시지", null);

        board().andExpect(jsonPath("$.data.emptyReasons", contains(NEVER_ANALYZED)));

    }

    @Test
    @DisplayName("분석했지만 경험 그룹화 기록이 없는 빈 보드는 계정 없는 커밋이 저장돼 있어도 한 줄뿐이다")
    void keepsNeverGroupedBoardToOneLine() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        verdict.insertCommit(repositoryId, "a".repeat(40), null, "메시지", null);

        board().andExpect(jsonPath("$.data.emptyReasons", contains(NOT_GROUPED_YET)));

    }

    @Test
    @DisplayName("마지막 분석보다 이르게 경험 그룹화를 한 빈 보드는 계정 없는 커밋이 저장돼 있어도 한 줄뿐이다")
    void keepsStaleGroupingBoardToOneLine() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT.minusSeconds(1));
        verdict.insertCommit(repositoryId, "a".repeat(40), null, "메시지", null);

        board().andExpect(jsonPath("$.data.emptyReasons", contains(NOT_GROUPED_YET)));

    }

    @Test
    @DisplayName("분석 뒤에 경험 그룹화를 했는데 후보가 0개인 빈 보드는 개수를 못 센 저장소도 이유 줄을 준다")
    void explainsGroupedEmptyBoardWithSharedLines() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT.plusSeconds(1));

        board()
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.emptyReasons", contains(UNCOUNTED)));

    }

    @Test
    @DisplayName("분석 뒤에 경험 그룹화를 했는데 후보가 0개인 빈 보드는 저장된 숫자로 만든 줄을 순서대로 준다")
    void explainsGroupedEmptyBoardWithRealNumbers() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT);
        verdict.setCounts(myRepoId, COUNTED_AT, 197, 52, 58, 23, 11);
        verdict.insertCommit(repositoryId, "a".repeat(40), "grow22", "feat: 세션 만료 처리", null);
        verdict.insertCommit(repositoryId, "b".repeat(40), null, "계정 없는 커밋", null);

        board()
                .andExpect(jsonPath("$.data.emptyReasons", contains("내 커밋 52개 / 팀 145개", "커밋 메시지 평균 14글자",
                        "PR 23건 · 리뷰 11건", "계정에 연결되지 않은 커밋 1개는 내 커밋으로 세지 않았어요")));

    }

    /** 커밋 행은 저장소 단위라 작성자가 나든 팀원이든 계정이 없든 읽어 둔 커밋으로 센다 */
    private void storeCommits(int count) {

        String[] authors = {"grow22", "teammate", null};
        for (int i = 0; i < count; i++) {
            verdict.insertCommit(repositoryId, String.valueOf((char) ('a' + i)).repeat(40), authors[i % 3], "메시지 " + i,
                    null);
        }

    }

    private ResultActions board() throws Exception {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return mvc.perform(get("/api/repos/{id}/candidates", MY_REPO)
                        .with(authentication(new UsernamePasswordAuthenticationToken(principal, null,
                                principal.getAuthorities()))))
                .andExpect(status().isOk());

    }

    private static String body(ResultActions result) throws Exception {

        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

    }
}
