package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.job.domain.JobRunner;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.SqlRecorder;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
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
        "gitory.consent.token-salt=5c0744940b5c369b",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.gitory.backend.support.SqlRecorder"
})
@AutoConfigureMockMvc
@Testcontainers
class CandidateCardApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID DIRECT_CARD = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobRunner runner;

    private TestFixtures fixtures;
    private CandidateCardFixtures candidateFixtures;

    private Long myUserId;
    private Long myRepoId;
    private Long prCandidate;
    private Long clusterCandidate;
    private Long refLessCandidate;
    private Long newCandidate;
    private Long otherNewCandidate;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidateFixtures = new CandidateCardFixtures(jdbc);

        myUserId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);

        prCandidate = candidateFixtures.insertCandidate(myRepoId, "PR", 42, "#42", "이메일 발송", "USED");
        clusterCandidate = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "2026-05-12", "서버 구축", "USED");
        refLessCandidate = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, null, "ref 없는 후보", "USED");
        newCandidate = candidateFixtures.insertNewCandidate(myRepoId, "아직 안 쓴 후보");
        otherNewCandidate = candidateFixtures.insertNewCandidate(myRepoId, "또 안 쓴 후보");
        candidateFixtures.insertCandidate(myRepoId, "PR", 7, "#7", "제외한 후보", "EXCLUDED");
        SqlRecorder.clear();

    }

    @Test
    @DisplayName("후보로 만든 카드의 상세는 후보의 공개 id·종류·ref·제목을 담고, 직접 작성 카드의 candidate 는 null 이다")
    void detailCarriesCandidateOfCandidateCardOnly() throws Exception {

        UUID cardId = candidateCard(prCandidate, "SUCCEEDED", false);
        fixtures.insertCard(DIRECT_CARD, myUserId);

        card(cardId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.id").value(candidateFixtures.publicIdOf(prCandidate).toString()))
                .andExpect(jsonPath("$.data.candidate.type").value("PR"))
                .andExpect(jsonPath("$.data.candidate.ref").value("#42"))
                .andExpect(jsonPath("$.data.candidate.title").value("이메일 발송"));
        card(DIRECT_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate").value(nullValue()))
                .andExpect(jsonPath("$.data.generation").value(nullValue()));

    }

    @Test
    @DisplayName("AI 가 ref 를 주지 않은 후보로 만든 카드도 화면이 글자로 받도록 ref 를 빈 글자로 내려준다")
    void missingRefBecomesEmptyText() throws Exception {

        UUID cardId = candidateCard(refLessCandidate, "SUCCEEDED", false);

        card(cardId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.type").value("COMMIT_CLUSTER"))
                .andExpect(jsonPath("$.data.candidate.ref").value(""));

    }

    @ParameterizedTest
    @CsvSource({
            "QUEUED, false, true, false",
            "RUNNING, false, true, false",
            "SUCCEEDED, false, false, false",
            "FAILED, false, false, false",
            "CANCELED, false, false, false",
            "SUCCEEDED, true, true, true",
            "FAILED, true, true, true"
    })
    @DisplayName("초안 Job 이 대기·실행 중이거나 카드가 일부만 만들어졌으면 generation 을 주고, 그 밖에는 null 이다")
    void generationFollowsJobStateAndPartialFlag(String jobState, boolean partial, boolean present, boolean expectedPartial)
            throws Exception {

        UUID cardId = candidateCard(prCandidate, jobState, partial);

        ResultActions result = card(cardId).andExpect(status().isOk());

        if (present) {
            result.andExpect(jsonPath("$.data.generation.jobId").value(generationJobOf(cardId).toString()))
                    .andExpect(jsonPath("$.data.generation.partial").value(expectedPartial));
        } else {
            result.andExpect(jsonPath("$.data.generation").value(nullValue()));
        }

    }

    @Test
    @DisplayName("초안 Job 을 찾을 수 없으면 끝난 것으로 보고 generation 을 비운다")
    void missingJobCountsAsEnded() throws Exception {

        UUID cardId = candidateCard(prCandidate, "RUNNING", false);
        jdbc.update("DELETE FROM analysis_job");

        card(cardId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generation").value(nullValue()));

    }

    @Test
    @DisplayName("카드를 만든 직후에는 generation 이 있고, 워커가 초안 Job 을 끝내면 사라지며 카드의 빈 1번 버전과 후보 연결은 그대로다")
    void generationEndsWhenWorkerFinishesDraftJob() throws Exception {

        CreatedIds created = createCards(newCandidate);
        card(created.cardIds().get(0))
                .andExpect(jsonPath("$.data.generation.jobId").value(created.jobId().toString()))
                .andExpect(jsonPath("$.data.generation.partial").value(false));

        runner.claimNext().ifPresent(runner::run);

        card(created.cardIds().get(0))
                .andExpect(jsonPath("$.data.generation").value(nullValue()))
                .andExpect(jsonPath("$.data.candidate.title").value("아직 안 쓴 후보"));
        assertThat(jdbc.queryForObject("SELECT state FROM analysis_job", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT generation_job_id::text FROM card", String.class))
                .isEqualTo(created.jobId().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version", Integer.class)).isEqualTo(1);

    }

    @Test
    @DisplayName("초안 Job 을 취소하면 generation 이 사라지고, 카드는 빈 1번 버전을 그대로 두며 후보도 USED 로 남고, 끝난 뒤의 취소는 아무것도 바꾸지 않는다")
    void cancelEndsGenerationAndKeepsCards() throws Exception {

        CreatedIds created = createCards(newCandidate, otherNewCandidate);

        mvc.perform(post("/api/jobs/{id}/cancel", created.jobId()).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CANCELED"))
                .andExpect(jsonPath("$.data.result.repoId").value(MY_REPO.toString()))
                .andExpect(jsonPath("$.data.result.cardIds[0]").value(created.cardIds().get(0).toString()))
                .andExpect(jsonPath("$.data.result.cardIds[1]").value(created.cardIds().get(1).toString()));

        card(created.cardIds().get(0)).andExpect(jsonPath("$.data.generation").value(nullValue()));
        assertThat(candidateFixtures.statusOf(newCandidate)).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT version_no FROM card_version", Integer.class)).containsExactly(1, 1);

        mvc.perform(post("/api/jobs/{id}/cancel", created.jobId()).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CANCELED"));

    }

    @Test
    @DisplayName("끝난 초안 Job 을 취소해도 SUCCEEDED 그대로이고 카드 id 가 담긴 result 를 돌려준다")
    void cancelAfterEndChangesNothing() throws Exception {

        CreatedIds created = createCards(newCandidate);
        runner.claimNext().ifPresent(runner::run);

        mvc.perform(post("/api/jobs/{id}/cancel", created.jobId()).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.cardIds[0]").value(created.cardIds().get(0).toString()));

    }

    @Test
    @DisplayName("멈춘 초안 Job 을 정리해 FAILED 로 끝내면 generation 이 사라지고 카드는 빈 1번 버전으로 남는다")
    void stuckCleanupEndsGeneration() throws Exception {

        CreatedIds created = createCards(newCandidate);
        runner.claimNext();
        jdbc.update("UPDATE analysis_job SET updated_at = now() - interval '2 hours'");

        runner.failStuckJobs();

        card(created.cardIds().get(0)).andExpect(jsonPath("$.data.generation").value(nullValue()));
        assertThat(jdbc.queryForObject("SELECT state FROM analysis_job", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT version_no FROM card_version", Integer.class)).containsExactly(1);

    }

    @Test
    @DisplayName("초안 Job 조회의 result 는 저장소 id 와 카드 id 를 담고 판정과 이유는 비우며, 대기 중일 때도 끝난 뒤에도 같다")
    void jobResultCarriesRepositoryAndCardIds() throws Exception {

        CreatedIds created = createCards(newCandidate, otherNewCandidate);
        String cardIds = created.cardIds().get(0) + "," + created.cardIds().get(1);

        for (int round = 0; round < 2; round++) {
            String body = mvc.perform(get("/api/jobs/{id}", created.jobId()).with(loggedInAs(myUserId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.type").value("DRAFT"))
                    .andExpect(jsonPath("$.data.result.repoId").value(MY_REPO.toString()))
                    .andExpect(jsonPath("$.data.result.verdict").value(nullValue()))
                    .andExpect(jsonPath("$.data.result.reasons").value(nullValue()))
                    .andReturn().getResponse().getContentAsString();
            List<String> found = JsonPath.read(body, "$.data.result.cardIds");
            assertThat(String.join(",", found)).isEqualTo(cardIds);
            runner.claimNext().ifPresent(runner::run);
        }

    }

    @Test
    @DisplayName("카드 목록은 후보로 만든 카드의 sourceType 을 후보 종류로, sourceLabel 을 후보 ref 로 주고 직접 작성 카드는 null 과 MANUAL 그대로이며, 후보는 한 번에 읽는다")
    void listCarriesCandidateTypeAndRef() throws Exception {

        candidateCard(prCandidate, "SUCCEEDED", false);
        candidateCard(clusterCandidate, "SUCCEEDED", false);
        candidateCard(refLessCandidate, "SUCCEEDED", false);
        fixtures.insertCard(DIRECT_CARD, myUserId);
        SqlRecorder.clear();

        String body = mvc.perform(get("/api/cards").with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> rows = JsonPath.read(body, "$.data");
        assertThat(rows).extracting(row -> row.get("title"))
                .containsExactlyInAnyOrder("후보 카드", "후보 카드", "후보 카드", "카드");
        assertThat(rows).extracting(row -> row.get("sourceType") + "/" + row.get("sourceLabel"))
                .containsExactlyInAnyOrder("PR/#42", "COMMIT_CLUSTER/2026-05-12", "COMMIT_CLUSTER/", "null/MANUAL");
        assertThat(SqlRecorder.count("select", "from candidate c1_0")).isEqualTo(1);

    }

    @Test
    @DisplayName("후보로 만든 카드를 확정하면 usedCandidateIds 에 그 후보 공개 id 를 담고, remainingCandidates 는 같은 저장소의 새 후보 수다")
    void confirmReportsUsedCandidateAndRemainingNewCandidates() throws Exception {

        UUID cardId = candidateCard(prCandidate, "SUCCEEDED", false);
        situationAndAction(cardIdOf(cardId));
        newCandidateOfMyOtherRepository();

        confirm(cardId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedCandidateIds.length()").value(1))
                .andExpect(jsonPath("$.data.usedCandidateIds[0]").value(candidateFixtures.publicIdOf(prCandidate).toString()))
                .andExpect(jsonPath("$.data.remainingCandidates").value(2))
                .andExpect(jsonPath("$.data.repoId").value(MY_REPO.toString()));

    }

    @Test
    @DisplayName("저장소가 붙은 직접 작성 카드를 확정하면 usedCandidateIds 는 비고 remainingCandidates 는 그 저장소의 새 후보 수이며, 저장소가 없으면 0 이다")
    void confirmOfDirectCardReportsRepositoryRemainingOnly() throws Exception {

        Long withRepo = fixtures.insertCard(DIRECT_CARD, myUserId);
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", myRepoId, withRepo);
        situationAndAction(withRepo);
        UUID noRepoCard = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        Long withoutRepo = fixtures.insertCard(noRepoCard, myUserId);
        situationAndAction(withoutRepo);
        newCandidateOfMyOtherRepository();

        confirm(DIRECT_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedCandidateIds.length()").value(0))
                .andExpect(jsonPath("$.data.remainingCandidates").value(2));
        confirm(noRepoCard)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedCandidateIds.length()").value(0))
                .andExpect(jsonPath("$.data.remainingCandidates").value(0))
                .andExpect(jsonPath("$.data.repoId").value(nullValue()));

    }

    private void newCandidateOfMyOtherRepository() {

        Long otherRepositoryId = fixtures.insertRepository(2L, "grow22", "gitory-docs");
        Long otherRepo = fixtures.insertUserRepository(
                UUID.fromString("22222222-2222-4222-8222-222222222222"), myUserId, otherRepositoryId);
        candidateFixtures.insertNewCandidate(otherRepo, "다른 저장소 후보");

    }

    private record CreatedIds(UUID jobId, List<UUID> cardIds) {
    }

    private CreatedIds createCards(Long... candidateIds) throws Exception {

        String ids = Arrays.stream(candidateIds)
                .map(id -> "\"" + candidateFixtures.publicIdOf(id) + "\"")
                .collect(Collectors.joining(","));
        String body = mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateIds\":[" + ids + "]}")
                        .with(loggedInAs(myUserId))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> cardIds = JsonPath.read(body, "$.data.cardIds");

        return new CreatedIds(UUID.fromString(JsonPath.read(body, "$.data.jobId")),
                cardIds.stream().map(UUID::fromString).toList());

    }

    /** 주어진 상태의 초안 Job 을 가리키는, 후보로 만든 카드를 넣고 그 카드 공개 id 를 돌려준다 */
    private UUID candidateCard(Long candidateId, String jobState, boolean partial) {

        UUID jobId = candidateFixtures.insertDraftJob(myUserId, myRepoId, jobState);

        return candidateFixtures.cardPublicIdOf(
                candidateFixtures.insertCandidateCard(myUserId, myRepoId, candidateId, jobId, partial));

    }

    private UUID generationJobOf(UUID cardId) {

        return jdbc.queryForObject("SELECT generation_job_id FROM card WHERE public_id = ?", UUID.class, cardId);

    }

    private Long cardIdOf(UUID cardId) {

        return jdbc.queryForObject("SELECT id FROM card WHERE public_id = ?", Long.class, cardId);

    }

    private void situationAndAction(Long cardId) {

        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "A", "행동", "USER_STATED", null);

    }

    private ResultActions card(UUID cardId) throws Exception {

        return mvc.perform(get("/api/cards/{id}", cardId).with(loggedInAs(myUserId)));

    }

    private ResultActions confirm(UUID cardId) throws Exception {

        return mvc.perform(post("/api/cards/{id}/confirm", cardId).with(loggedInAs(myUserId)).with(csrf()));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
