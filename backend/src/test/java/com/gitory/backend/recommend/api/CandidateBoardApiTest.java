package com.gitory.backend.recommend.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.StoredActivityFixtures;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class CandidateBoardApiTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TEAMMATE_REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHERS_REPO = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID UNKNOWN_REPO = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant ANALYZED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final String NEVER_ANALYZED = "아직 정리하지 않은 저장소예요";
    private static final String NOT_GROUPED_YET = "아직 이 저장소의 후보를 만들지 못했어요";
    private static final String[] BOARD_KEYS = {"repoId", "verdict", "partial", "retryAfterSeconds", "readCoverage",
            "emptyReasons", "candidates"};
    private static final String[] CANDIDATE_KEYS = {"id", "repoId", "type", "status", "ref", "title", "reason", "meta",
            "weak", "score", "commits", "usedByCardId"};
    private static final String[] META_KEYS = {"commits", "files", "reviewComments", "days", "codeRatio"};
    private static final String[] COMMIT_KEYS = {"sha", "message", "path", "at", "included", "url"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private StoredActivityFixtures activity;
    private CandidateBoardFixtures board;
    private Long myUserId;
    private Long teammateUserId;
    private Long othersUserId;
    private Long repositoryId;
    private Long myRepoId;
    private Long teammateRepoId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        activity = new StoredActivityFixtures(jdbc);
        board = new CandidateBoardFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        teammateUserId = fixtures.insertUser(2L, "teammate");
        othersUserId = fixtures.insertUser(3L, "someone-else");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long othersRepository = fixtures.insertRepository(2L, "someone-else", "private-work");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        teammateRepoId = fixtures.insertUserRepository(TEAMMATE_REPO, teammateUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, othersRepository);

    }

    @Test
    @DisplayName("로그인하지 않고 조회하면 401 이다")
    void rejectsAnonymousRequest() throws Exception {

        mvc.perform(get("/api/repos/{id}/candidates", MY_REPO))
                .andExpect(status().isUnauthorized());

    }

    @Test
    @DisplayName("UUID 가 아닌 id 와 없는 저장소와 남의 저장소는 똑같은 본문의 404 NOT_FOUND 이다")
    void answersTheSame404ForMalformedUnknownAndForeign() throws Exception {

        String malformed = body(board(myUserId, "not-a-uuid").andExpect(status().isNotFound()));
        String unknown = body(board(myUserId, UNKNOWN_REPO.toString()).andExpect(status().isNotFound()));
        String foreign = body(board(myUserId, OTHERS_REPO.toString()).andExpect(status().isNotFound()));

        assertThat(JsonPath.<String>read(malformed, "$.error.code")).isEqualTo("NOT_FOUND");
        assertThat(JsonPath.<Object>read(malformed, "$.data")).isNull();
        assertThat(unknown).isEqualTo(malformed);
        assertThat(foreign).isEqualTo(malformed);

    }

    @Test
    @DisplayName("남의 저장소를 조회하면 그 저장소 연결로 VIEW_CANDIDATES 거절 기록이 하나 남는다")
    void recordsOneDeniedEventForForeignRepository() throws Exception {

        board(myUserId, OTHERS_REPO.toString()).andExpect(status().isNotFound());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_CANDIDATES", othersRepoId, "DENIED"));

    }

    @Test
    @DisplayName("잘못된 id 나 없는 저장소를 조회하면 기록을 남기지 않는다")
    void recordsNothingForMalformedOrUnknown() throws Exception {

        board(myUserId, "not-a-uuid").andExpect(status().isNotFound());
        board(myUserId, UNKNOWN_REPO.toString()).andExpect(status().isNotFound());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("내 저장소를 조회하면 200 이고 성공 기록은 남기지 않는다")
    void recordsNothingOnSuccess() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");

        board(myUserId, MY_REPO.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.repoId").value(MY_REPO.toString()));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("보드와 후보와 메타와 커밋의 모든 키가 있고 값이 없는 칸은 키를 빼지 않고 null 로 둔다")
    void keepsEveryNullableKeyAsNull() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        Long cluster = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "세션 정리", "0.5");
        Long commit = activity.insertCommit(repositoryId, sha('a'), Instant.parse("2026-09-01T00:00:00Z"), 1);
        board.insertCandidateCommit(cluster, commit, true, "");

        String json = body(board(myUserId, MY_REPO.toString()).andExpect(status().isOk()));
        Map<String, Object> data = JsonPath.read(json, "$.data");
        List<Map<String, Object>> candidates = JsonPath.read(json, "$.data.candidates");
        Map<String, Object> prJson = candidates.get(0);
        Map<String, Object> clusterJson = candidates.get(1);
        Map<String, Object> prMeta = JsonPath.read(prJson, "$.meta");
        List<Map<String, Object>> clusterCommits = JsonPath.read(clusterJson, "$.commits");

        assertThat(data).containsOnlyKeys(BOARD_KEYS);
        assertThat(data.get("retryAfterSeconds")).isNull();
        assertThat(data.get("readCoverage")).isNull();
        assertThat(data.get("partial")).isEqualTo(false);
        assertThat(prJson).containsOnlyKeys(CANDIDATE_KEYS);
        assertThat(prJson.get("commits")).isNull();
        assertThat(prJson.get("usedByCardId")).isNull();
        assertThat(prMeta).containsOnlyKeys(META_KEYS);
        assertThat(prMeta.get("days")).isNull();
        assertThat(prMeta.get("codeRatio")).isNull();
        assertThat(clusterJson).containsOnlyKeys(CANDIDATE_KEYS);
        assertThat(clusterCommits.get(0)).containsOnlyKeys(COMMIT_KEYS);

    }

    @Test
    @DisplayName("다른 저장소의 후보는 섞이지 않는다")
    void doesNotMixCandidatesOfAnotherRepository() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "내 후보", "0.9");
        board.insertCandidate(othersRepoId, "PR", 7, "NEW", "남의 후보", "0.8");

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates", hasSize(1)))
                .andExpect(jsonPath("$.data.candidates[0].title").value("내 후보"));

    }

    @Test
    @DisplayName("같은 GitHub 저장소에 연결한 팀원의 후보와 경험 그룹화 기록과 분석 시각은 내 보드에 영향을 주지 않는다")
    void ignoresTeammateConnectedToTheSameGithubRepository() throws Exception {

        Long shared = activity.insertCommit(repositoryId, sha('a'), Instant.parse("2026-09-01T00:00:00Z"), 1);
        Long teammateCluster = board.insertCandidate(teammateRepoId, "COMMIT_CLUSTER", null, "NEW", "팀원 묶음", "0.9");
        board.insertCandidateCommit(teammateCluster, shared, true, "A.java");
        board.insertGrouping(teammateRepoId, ANALYZED_AT.plusSeconds(60));
        board.setLastAnalyzedAt(teammateRepoId, ANALYZED_AT);

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.candidates", hasSize(0)))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value(NEVER_ANALYZED));

    }

    @Test
    @DisplayName("내 후보와 경험 그룹화 기록과 분석 시각이 있어도 같은 GitHub 저장소의 팀원 보드는 그대로 빈 보드다")
    void teammateBoardStaysEmptyWhenOnlyIHaveCandidates() throws Exception {

        Long shared = activity.insertCommit(repositoryId, sha('a'), Instant.parse("2026-09-01T00:00:00Z"), 1);
        Long cluster = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "내 묶음", "0.9");
        board.insertCandidateCommit(cluster, shared, true, "A.java");
        board.insertGrouping(myRepoId, ANALYZED_AT.plusSeconds(60));
        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);

        board(teammateUserId, TEAMMATE_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.candidates", hasSize(0)))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value(NEVER_ANALYZED));

    }

    @Test
    @DisplayName("팀원이 마지막 분석 뒤에 경험 그룹화를 했어도 내 경험 그룹화 기록이 없으면 내 빈 보드는 '아직 이 저장소의 후보를 만들지 못했어요' 한 줄이다")
    void ignoresTeammateGroupingWhenIHaveNone() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(teammateRepoId, ANALYZED_AT.plusSeconds(60));

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.emptyReasons", hasSize(1)))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value(NOT_GROUPED_YET));

    }

    @Test
    @DisplayName("분석한 적 없는 저장소의 빈 보드는 EMPTY 와 '아직 정리하지 않은 저장소예요' 한 줄이다")
    void explainsNeverAnalyzedRepository() throws Exception {

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.emptyReasons", hasSize(1)))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value(NEVER_ANALYZED));

    }

    @Test
    @DisplayName("분석했지만 경험 그룹화 기록이 없는 빈 보드는 '아직 이 저장소의 후보를 만들지 못했어요' 한 줄이다")
    void explainsAnalyzedButNeverGrouped() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.emptyReasons", hasSize(1)))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value(NOT_GROUPED_YET));

    }

    @Test
    @DisplayName("경험 그룹화 시각이 마지막 분석보다 이르면 빈 보드는 '아직 이 저장소의 후보를 만들지 못했어요' 한 줄이다")
    void explainsGroupedBeforeLastAnalysis() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT.minusSeconds(1));

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.emptyReasons", hasSize(1)))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value(NOT_GROUPED_YET));

    }

    @Test
    @DisplayName("마지막 분석 뒤에 경험 그룹화를 했는데 후보가 0개면 EMPTY 이고 개수를 못 센 저장소의 이유 줄은 '내 커밋 수를 아직 세지 못했어요' 하나다")
    void explainsUncountedRepositoryWhenGroupedAfterLastAnalysis() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT.plusSeconds(1));

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.emptyReasons", hasSize(1)))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value("내 커밋 수를 아직 세지 못했어요"));

    }

    @Test
    @DisplayName("경험 그룹화 시각이 마지막 분석 시각과 같으면 이르지 않으므로 경험 그룹화를 한 보드의 이유 줄을 준다")
    void explainsGroupedBoardWhenGroupedAtTheSameInstantAsLastAnalysis() throws Exception {

        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT);

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("EMPTY"))
                .andExpect(jsonPath("$.data.emptyReasons[0]").value("내 커밋 수를 아직 세지 못했어요"));

    }

    @Test
    @DisplayName("후보가 있으면 경험 그룹화 시각이 오래돼도 OK 이고 이유 줄을 붙이지 않는다")
    void addsNoReasonWhenCandidatesExist() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT.minusSeconds(3600));

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.emptyReasons", hasSize(0)));

    }

    @Test
    @DisplayName("제외된 후보만 있는 저장소도 EMPTY 가 아니라 OK 라서 제외 복원 길이 남는다")
    void treatsExcludedOnlyRepositoryAsOk() throws Exception {

        board.insertCandidate(myRepoId, "PR", 42, "EXCLUDED", "제외한 후보", "0.9");

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.verdict").value("OK"))
                .andExpect(jsonPath("$.data.candidates", hasSize(1)))
                .andExpect(jsonPath("$.data.candidates[0].status").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.emptyReasons", hasSize(0)));

    }

    @Test
    @DisplayName("NEW·USED·EXCLUDED 후보를 모두 돌려주고 점수 높은 순에 같으면 먼저 저장한 순이며 점수가 없으면 0 으로 맨 뒤다")
    void ordersAllStatusesByScoreThenInsertion() throws Exception {

        board.insertCandidate(myRepoId, "PR", 1, "NEW", "첫째 0.5", "0.5");
        board.insertCandidate(myRepoId, "PR", 2, "USED", "높음 0.9", "0.9");
        board.insertCandidate(myRepoId, "PR", 3, "EXCLUDED", "둘째 0.5", "0.5");
        board.insertCandidate(myRepoId, "PR", 4, "NEW", "점수 없음", null);
        board.insertCandidate(myRepoId, "PR", 5, "NEW", "낮음 0.1", "0.1");

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates", hasSize(5)))
                .andExpect(jsonPath("$.data.candidates[0].title").value("높음 0.9"))
                .andExpect(jsonPath("$.data.candidates[0].status").value("USED"))
                .andExpect(jsonPath("$.data.candidates[1].title").value("첫째 0.5"))
                .andExpect(jsonPath("$.data.candidates[2].title").value("둘째 0.5"))
                .andExpect(jsonPath("$.data.candidates[2].status").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.candidates[3].title").value("낮음 0.1"))
                .andExpect(jsonPath("$.data.candidates[4].title").value("점수 없음"))
                .andExpect(jsonPath("$.data.candidates[4].score").value(0.0))
                .andExpect(jsonPath("$.data.candidates[0].score").value(0.9));

    }

    @Test
    @DisplayName("meta.commits 는 포함된 커밋만 세고 나머지 숫자는 경험 그룹화 때 저장한 칸 그대로다")
    void countsOnlyIncludedCommitsAndReadsStoredMeta() throws Exception {

        Long pr = board.insertCandidate(myRepoId, "PR", 42, "NEW", "로그인 개선", "0.9");
        board.setDisplay(pr, "#42", true, 7, 3, 12, "0.8125");
        board.insertCandidateCommit(pr, activity.insertCommit(repositoryId, sha('a'), Instant.now(), 1), true, "");
        board.insertCandidateCommit(pr, activity.insertCommit(repositoryId, sha('b'), Instant.now(), 1), true, "");
        board.insertCandidateCommit(pr, activity.insertCommit(repositoryId, sha('c'), Instant.now(), 1), false, "");

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates[0].meta.commits").value(2))
                .andExpect(jsonPath("$.data.candidates[0].meta.files").value(7))
                .andExpect(jsonPath("$.data.candidates[0].meta.reviewComments").value(3))
                .andExpect(jsonPath("$.data.candidates[0].meta.days").value(12))
                .andExpect(jsonPath("$.data.candidates[0].meta.codeRatio").value(0.8125))
                .andExpect(jsonPath("$.data.candidates[0].weak").value(true))
                .andExpect(jsonPath("$.data.candidates[0].title").value("로그인 개선"))
                .andExpect(jsonPath("$.data.candidates[0].reason").value("추천 이유"))
                .andExpect(jsonPath("$.data.candidates[0].type").value("PR"))
                .andExpect(jsonPath("$.data.candidates[0].repoId").value(MY_REPO.toString()))
                .andExpect(jsonPath("$.data.candidates[0].id").value(board.publicIdOf(pr).toString()));

    }

    @Test
    @DisplayName("ref 는 저장한 값을 쓰고 없으면 PR 후보는 PR 번호에 샵을 붙이며 그 밖의 후보는 빈 글자다")
    void fallsBackRefOnlyWhenNothingStored() throws Exception {

        Long stored = board.insertCandidate(myRepoId, "PR", 1, "NEW", "저장한 ref", "0.9");
        board.setDisplay(stored, "#99", false, 0, 0, null, null);
        board.insertCandidate(myRepoId, "PR", 42, "NEW", "ref 없는 PR", "0.8");
        board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "ref 없는 묶음", "0.7");
        board.insertCandidate(myRepoId, "MANUAL", null, "NEW", "ref 없는 직접 추가", "0.6");

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates[0].ref").value("#99"))
                .andExpect(jsonPath("$.data.candidates[1].ref").value("#42"))
                .andExpect(jsonPath("$.data.candidates[2].ref").value(""))
                .andExpect(jsonPath("$.data.candidates[3].ref").value(""));

    }

    @Test
    @DisplayName("PR 후보와 직접 추가 후보의 commits 는 커밋이 있어도 null 이다")
    void returnsNullCommitsForPrAndManualCandidates() throws Exception {

        Long pr = board.insertCandidate(myRepoId, "PR", 42, "NEW", "PR 후보", "0.9");
        Long manual = board.insertCandidate(myRepoId, "MANUAL", null, "NEW", "직접 추가", "0.8");
        Long commit = activity.insertCommit(repositoryId, sha('a'), Instant.now(), 1);
        board.insertCandidateCommit(pr, commit, true, "A.java");
        board.insertCandidateCommit(manual, commit, true, "A.java");

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates[0].commits").value(nullValue()))
                .andExpect(jsonPath("$.data.candidates[0].meta.commits").value(1))
                .andExpect(jsonPath("$.data.candidates[1].commits").value(nullValue()))
                .andExpect(jsonPath("$.data.candidates[1].meta.commits").value(1));

    }

    @Test
    @DisplayName("커밋 묶음 후보는 가운데에서 뺀 커밋도 저장한 순서 그대로 같은 줄에 두고 전체 sha 와 메시지와 표시 경로와 ISO 시각과 포함 여부와 커밋 주소를 돌려준다")
    void listsEveryCommitOfClusterWithDisplayPathAndUrl() throws Exception {

        Long cluster = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "세션 정리", "0.7");
        Long removed = activity.insertCommit(repositoryId, sha('c'), Instant.parse("2026-09-05T00:00:00Z"), 1);
        Long oldest = activity.insertCommit(repositoryId, sha('a'), Instant.parse("2026-09-01T00:00:00Z"), 1);
        Long first = activity.insertCommit(repositoryId, sha('b'), Instant.parse("2026-09-03T01:02:03Z"), 2);
        board.setCommitMessage(first, "feat: 세션 만료");
        board.insertCandidateCommit(cluster, first, true, "src/Session.java");
        board.insertCandidateCommit(cluster, removed, true, "src/Token.java");
        board.insertCandidateCommit(cluster, oldest, true, "");
        board.excludeCommit(cluster, removed);

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates[0].commits", hasSize(3)))
                .andExpect(jsonPath("$.data.candidates[0].commits[0].sha").value(sha('b')))
                .andExpect(jsonPath("$.data.candidates[0].commits[0].message").value("feat: 세션 만료"))
                .andExpect(jsonPath("$.data.candidates[0].commits[0].path").value("src/Session.java"))
                .andExpect(jsonPath("$.data.candidates[0].commits[0].at").value("2026-09-03T01:02:03Z"))
                .andExpect(jsonPath("$.data.candidates[0].commits[0].included").value(true))
                .andExpect(jsonPath("$.data.candidates[0].commits[0].url")
                        .value("https://github.com/grow22/gitory/commit/" + sha('b')))
                .andExpect(jsonPath("$.data.candidates[0].commits[1].sha").value(sha('c')))
                .andExpect(jsonPath("$.data.candidates[0].commits[1].included").value(false))
                .andExpect(jsonPath("$.data.candidates[0].commits[2].sha").value(sha('a')))
                .andExpect(jsonPath("$.data.candidates[0].commits[2].included").value(true))
                .andExpect(jsonPath("$.data.candidates[0].commits[2].path").value(""))
                .andExpect(jsonPath("$.data.candidates[0].meta.commits").value(2));

    }

    @Test
    @DisplayName("커밋 메시지가 없으면 빈 글자로, 작성 시각을 모르면 빈 글자로 돌려줘 화면 검사가 깨지지 않는다")
    void answersEmptyStringsForMissingMessageAndTime() throws Exception {

        Long cluster = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "세션 정리", "0.7");
        Long commit = activity.insertCommit(repositoryId, sha('a'), null, null);
        board.insertCandidateCommit(cluster, commit, true, "");

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates[0].commits[0].message").value(""))
                .andExpect(jsonPath("$.data.candidates[0].commits[0].at").value(""));

    }

    @Test
    @DisplayName("USED 후보는 지워지지 않은 카드의 공개 id 를 돌려주고 NEW 와 EXCLUDED 후보는 카드가 있어도 null 이다")
    void returnsCardIdOnlyForUsedCandidates() throws Exception {

        UUID usedCard = UUID.fromString("77777777-7777-4777-8777-777777777777");
        Long used = board.insertCandidate(myRepoId, "PR", 1, "USED", "쓴 후보", "0.9");
        Long fresh = board.insertCandidate(myRepoId, "PR", 2, "NEW", "새 후보", "0.8");
        Long excluded = board.insertCandidate(myRepoId, "PR", 3, "EXCLUDED", "제외 후보", "0.7");
        board.insertCardOf(used, myUserId, usedCard, false);
        board.insertCardOf(fresh, myUserId, UUID.randomUUID(), false);
        board.insertCardOf(excluded, myUserId, UUID.randomUUID(), false);

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates[0].usedByCardId").value(usedCard.toString()))
                .andExpect(jsonPath("$.data.candidates[1].usedByCardId").value(nullValue()))
                .andExpect(jsonPath("$.data.candidates[2].usedByCardId").value(nullValue()));

    }

    @Test
    @DisplayName("지운 카드는 usedByCardId 로 돌려주지 않는다")
    void doesNotReturnDeletedCard() throws Exception {

        Long used = board.insertCandidate(myRepoId, "PR", 1, "USED", "쓴 후보", "0.9");
        board.insertCardOf(used, myUserId, UUID.randomUUID(), true);

        board(myUserId, MY_REPO.toString())
                .andExpect(jsonPath("$.data.candidates[0].status").value("USED"))
                .andExpect(jsonPath("$.data.candidates[0].usedByCardId").value(nullValue()));

    }

    private ResultActions board(Long userId, String id) throws Exception {

        return mvc.perform(get("/api/repos/{id}/candidates", id).with(loggedInAs(userId)));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private static String body(ResultActions result) throws Exception {

        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

    }

    private static String sha(char c) {

        return String.valueOf(c).repeat(40);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
