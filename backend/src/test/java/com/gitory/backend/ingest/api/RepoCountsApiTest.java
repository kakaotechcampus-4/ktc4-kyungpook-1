package com.gitory.backend.ingest.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.consent.port.GithubOwnerResponse;
import com.gitory.backend.consent.port.GithubRepositoryResponse;
import com.gitory.backend.support.CandidateFixtures;
import com.gitory.backend.support.SqlRecorder;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.gitory.backend.support.SqlRecorder"
})
@AutoConfigureMockMvc
@Testcontainers
class RepoCountsApiTest {

    private static final Instant CREATED = Instant.parse("2026-09-10T03:00:00Z");
    private static final Instant PUSHED = Instant.parse("2026-10-05T01:00:00Z");
    private static final Instant ANALYZED = Instant.parse("2026-10-08T05:00:00Z");

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
    private CandidateFixtures candidates;
    private Long myUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        candidates = new CandidateFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("후보 수는 상태와 종류를 가리지 않고 센다")
    void countsCandidatesOfEveryStatusAndType() throws Exception {

        githubReturns(repo(100L, "gitory"));
        Long connection = connectionOf(listIds().get(0));
        candidates.insertCandidate(connection, "PR", 7, "NEW");
        candidates.insertCandidate(connection, "COMMIT_CLUSTER", null, "USED");
        candidates.insertCandidate(connection, "COMMIT_CLUSTER", null, "EXCLUDED");
        candidates.insertCandidate(connection, "MANUAL", null, "NEW");

        list().andExpect(jsonPath("$.data[0].candidateCount").value(4));

    }

    @Test
    @DisplayName("카드 수는 확정한 카드만 센다 — 작성 중인 카드는 세지 않는다")
    void countsOnlyConfirmedCards() throws Exception {

        githubReturns(repo(100L, "gitory"));
        Long connection = connectionOf(listIds().get(0));
        card(connection, true);
        card(connection, true);
        card(connection, false);

        list().andExpect(jsonPath("$.data[0].cardCount").value(2));

    }

    @Test
    @DisplayName("지운 확정 카드는 세지 않는다")
    void ignoresDeletedConfirmedCard() throws Exception {

        githubReturns(repo(100L, "gitory"));
        Long connection = connectionOf(listIds().get(0));
        card(connection, true);
        Long deleted = card(connection, true);
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", deleted);

        list().andExpect(jsonPath("$.data[0].cardCount").value(1));

    }

    @Test
    @DisplayName("저장소가 없는 확정 카드는 어느 저장소에도 세지 않는다")
    void ignoresCardWithoutRepository() throws Exception {

        githubReturns(repo(100L, "gitory"));
        listIds();
        card(null, true);

        list().andExpect(jsonPath("$.data[0].cardCount").value(0));

    }

    @Test
    @DisplayName("같은 GitHub 저장소를 연결한 팀원의 후보와 카드는 내 숫자에 들어오지 않는다")
    void ignoresTeammatesCandidatesAndCards() throws Exception {

        githubReturns(repo(100L, "gitory"));
        Long connection = connectionOf(listIds().get(0));
        candidates.insertCandidate(connection, "PR", 7, "NEW");
        card(connection, true);
        Long teammateId = fixtures.insertUser(2L, "taehun0208");
        Long teammateConnection = fixtures.insertUserRepository(UUID.randomUUID(), teammateId,
                jdbc.queryForObject("SELECT id FROM repository WHERE github_repo_id = 100", Long.class));
        candidates.insertCandidate(teammateConnection, "PR", 8, "NEW");
        candidates.insertCandidate(teammateConnection, "PR", 9, "USED");
        cardOf(teammateId, teammateConnection, true);
        cardOf(teammateId, teammateConnection, true);

        list()
                .andExpect(jsonPath("$.data[0].candidateCount").value(1))
                .andExpect(jsonPath("$.data[0].cardCount").value(1));

    }

    @Test
    @DisplayName("저장소마다 자기 후보 수와 카드 수가 내려온다")
    void countsEachRepositoryApart() throws Exception {

        githubReturns(repo(100L, "alpha"), repo(200L, "beta"));
        List<String> ids = listIds();
        Long alpha = connectionOf(ids.get(0));
        Long beta = connectionOf(ids.get(1));
        candidates.insertCandidate(alpha, "PR", 7, "NEW");
        candidates.insertCandidate(beta, "PR", 7, "NEW");
        candidates.insertCandidate(beta, "PR", 8, "NEW");
        card(beta, true);
        card(beta, true);
        card(beta, true);

        list()
                .andExpect(jsonPath("$.data[0].name").value("alpha"))
                .andExpect(jsonPath("$.data[0].candidateCount").value(1))
                .andExpect(jsonPath("$.data[0].cardCount").value(0))
                .andExpect(jsonPath("$.data[1].name").value("beta"))
                .andExpect(jsonPath("$.data[1].candidateCount").value(2))
                .andExpect(jsonPath("$.data[1].cardCount").value(3));

    }

    @Test
    @DisplayName("상세의 후보 수와 카드 수는 목록의 같은 저장소 한 줄과 같다")
    void detailHasTheSameCountsAsTheListRow() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);
        Long connection = connectionOf(id);
        candidates.insertCandidate(connection, "PR", 7, "NEW");
        candidates.insertCandidate(connection, "PR", 8, "USED");
        card(connection, true);

        detail(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(2))
                .andExpect(jsonPath("$.data.cardCount").value(1));

    }

    @Test
    @DisplayName("분석한 적 없고 후보도 없으면 후보 수는 null 이다")
    void candidateCountIsNullBeforeFirstAnalysis() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);

        list().andExpect(jsonPath("$.data[0].candidateCount").value(nullValue()));
        detail(id).andExpect(jsonPath("$.data.candidateCount").value(nullValue()));

    }

    @Test
    @DisplayName("분석은 했지만 후보를 묶은 기록이 없으면 후보 수는 null 이다")
    void candidateCountIsNullWhenAnalyzedButNeverGrouped() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);
        analyzedAt(connectionOf(id), ANALYZED);

        list().andExpect(jsonPath("$.data[0].candidateCount").value(nullValue()));
        detail(id).andExpect(jsonPath("$.data.candidateCount").value(nullValue()));

    }

    @Test
    @DisplayName("마지막 분석보다 이르게 묶었고 후보가 없으면 후보 수는 null 이다")
    void candidateCountIsNullWhenGroupedBeforeLastAnalysis() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);
        Long connection = connectionOf(id);
        analyzedAt(connection, ANALYZED);
        groupedAt(connection, ANALYZED.minusSeconds(1));

        list().andExpect(jsonPath("$.data[0].candidateCount").value(nullValue()));
        detail(id).andExpect(jsonPath("$.data.candidateCount").value(nullValue()));

    }

    @Test
    @DisplayName("마지막 분석 뒤에 묶었는데 후보가 없으면 후보 수는 실제 0 이다")
    void candidateCountIsZeroWhenGroupedAfterLastAnalysis() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);
        Long connection = connectionOf(id);
        analyzedAt(connection, ANALYZED);
        groupedAt(connection, ANALYZED.plusSeconds(1));

        list().andExpect(jsonPath("$.data[0].candidateCount").value(0));
        detail(id).andExpect(jsonPath("$.data.candidateCount").value(0));

    }

    @Test
    @DisplayName("마지막 분석과 같은 시각에 묶었어도 묶은 것으로 보아 후보 수는 0 이다")
    void candidateCountIsZeroWhenGroupedAtTheLastAnalysisInstant() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);
        Long connection = connectionOf(id);
        analyzedAt(connection, ANALYZED);
        groupedAt(connection, ANALYZED);

        list().andExpect(jsonPath("$.data[0].candidateCount").value(0));
        detail(id).andExpect(jsonPath("$.data.candidateCount").value(0));

    }

    @Test
    @DisplayName("분석 시각이 없으면 묶은 기록이 있어도 후보가 없는 후보 수는 null 이다")
    void candidateCountIsNullWithoutAnalysisTimeEvenIfGrouped() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);
        groupedAt(connectionOf(id), ANALYZED);

        list().andExpect(jsonPath("$.data[0].candidateCount").value(nullValue()));
        detail(id).andExpect(jsonPath("$.data.candidateCount").value(nullValue()));

    }

    @Test
    @DisplayName("후보가 5개 있으면 마지막 분석보다 이르게 묶었어도 실제 후보 수가 내려온다")
    void candidateCountIsRealWhenGroupedBeforeLastAnalysis() throws Exception {

        githubReturns(repo(100L, "gitory"));
        String id = listIds().get(0);
        Long connection = connectionOf(id);
        analyzedAt(connection, ANALYZED);
        groupedAt(connection, ANALYZED.minusSeconds(60));
        for (int number = 1; number <= 5; number++) {
            candidates.insertCandidate(connection, "PR", number, "NEW");
        }

        list().andExpect(jsonPath("$.data[0].candidateCount").value(5));
        detail(id).andExpect(jsonPath("$.data.candidateCount").value(5));

    }

    @Test
    @DisplayName("저장소마다 자기 묶은 시각으로 후보 수의 null 과 0 을 따로 가린다")
    void judgesNullOrZeroPerRepository() throws Exception {

        githubReturns(repo(100L, "alpha"), repo(200L, "beta"));
        List<String> ids = listIds();
        Long alpha = connectionOf(ids.get(0));
        Long beta = connectionOf(ids.get(1));
        analyzedAt(alpha, ANALYZED);
        analyzedAt(beta, ANALYZED);
        groupedAt(alpha, ANALYZED.plusSeconds(1));
        groupedAt(beta, ANALYZED.minusSeconds(1));

        list()
                .andExpect(jsonPath("$.data[0].candidateCount").value(0))
                .andExpect(jsonPath("$.data[1].candidateCount").value(nullValue()));

    }

    @Test
    @DisplayName("분석 시각이 없어도 후보가 있으면 실제 후보 수가 내려온다")
    void candidateCountIsRealWhenCandidatesExistWithoutAnalysisTime() throws Exception {

        githubReturns(repo(100L, "gitory"));
        Long connection = connectionOf(listIds().get(0));
        candidates.insertCandidate(connection, "MANUAL", null, "NEW");

        list().andExpect(jsonPath("$.data[0].candidateCount").value(1));

    }

    @Test
    @DisplayName("저장소가 하나도 없는 사용자는 빈 배열을 받고 후보 수·묶은 시각·카드 수 조회는 보내지 않는다")
    void userWithoutRepositoriesGetsEmptyList() throws Exception {

        githubReturns();
        SqlRecorder.clear();

        list()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        assertThat(SqlRecorder.count("select", "from candidate c1_0")).isZero();
        assertThat(SqlRecorder.count("select", "from candidate_grouping")).isZero();
        assertThat(SqlRecorder.count("select", "from card")).isZero();

    }

    @Test
    @DisplayName("저장소가 여러 개여도 후보 수 조회와 묶은 시각 조회와 카드 수 조회는 각각 한 번이다")
    void countsWithOneStatementPerKind() throws Exception {

        githubReturns(repo(100L, "alpha"), repo(200L, "beta"), repo(300L, "gamma"));
        listIds();
        SqlRecorder.clear();

        list().andExpect(jsonPath("$.data.length()").value(3));

        assertThat(SqlRecorder.count("select", "from candidate c1_0")).isEqualTo(1);
        assertThat(SqlRecorder.count("select", "from candidate_grouping")).isEqualTo(1);
        assertThat(SqlRecorder.count("select", "from card")).isEqualTo(1);

    }

    private void githubReturns(GithubRepositoryResponse... repositories) {

        given(github.repositories(anyLong())).willReturn(List.of(repositories));

    }

    private static GithubRepositoryResponse repo(long githubRepoId, String name) {

        return new GithubRepositoryResponse(githubRepoId, name, new GithubOwnerResponse("grow22"),
                false, "Java", "main", CREATED, PUSHED);

    }

    private ResultActions list() throws Exception {

        return mvc.perform(get("/api/repos").with(loggedIn()));

    }

    private ResultActions detail(String id) throws Exception {

        return mvc.perform(get("/api/repos/{id}", id).with(loggedIn()));

    }

    private List<String> listIds() throws Exception {

        String body = list().andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.data[*].id");

    }

    private Long connectionOf(String publicId) {

        return jdbc.queryForObject("SELECT id FROM user_repository WHERE public_id = ?::uuid", Long.class, publicId);

    }

    private void analyzedAt(Long connection, Instant at) {

        jdbc.update("UPDATE user_repository SET last_analyzed_at = ? WHERE id = ?", Timestamp.from(at), connection);

    }

    private void groupedAt(Long connection, Instant at) {

        jdbc.update("INSERT INTO candidate_grouping (user_repository_id, input_fingerprint, grouping_version, grouped_at) VALUES (?, 'f', 1, ?)",
                connection, Timestamp.from(at));

    }

    private Long card(Long connection, boolean confirmed) {

        return cardOf(myUserId, connection, confirmed);

    }

    private Long cardOf(Long userId, Long connection, boolean confirmed) {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", connection, cardId);
        if (confirmed) {
            fixtures.confirmCard(cardId);
        }

        return cardId;

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
