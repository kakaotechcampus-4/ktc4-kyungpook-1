package com.gitory.backend.job.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
class CardCreationApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MY_OTHER_REPO = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHERS_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private CandidateCardFixtures candidateFixtures;

    private Long myUserId;
    private Long othersUserId;
    private Long myRepoId;
    private Long myOtherRepoId;
    private Long othersRepoId;
    private Long commitId;
    private Long withCommit;
    private Long withoutCommit;
    private Long onlyExcludedCommit;
    private Long second;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidateFixtures = new CandidateCardFixtures(jdbc);

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long otherRepositoryId = fixtures.insertRepository(2L, "grow22", "gitory-docs");
        commitId = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        Long excludedCommitId = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        myOtherRepoId = fixtures.insertUserRepository(MY_OTHER_REPO, myUserId, otherRepositoryId);
        othersRepoId = fixtures.insertUserRepository(OTHERS_REPO, othersUserId, repositoryId);

        withCommit = candidateFixtures.insertCandidate(myRepoId, "PR", 42, "#42", "이메일 발송", "NEW");
        candidateFixtures.insertCandidateCommit(withCommit, commitId, true);
        withoutCommit = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "5.12", "서버 구축", "NEW");
        onlyExcludedCommit = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "5.13", "빠진 커밋", "NEW");
        candidateFixtures.insertCandidateCommit(onlyExcludedCommit, excludedCommitId, false);
        second = candidateFixtures.insertNewCandidate(myRepoId, "둘째");

    }

    @Test
    @DisplayName("후보를 골라 요청하면 카드 초안 Job 하나를 QUEUED 로 만들고, 후보를 보낸 순서대로 카드 id 를 돌려준다")
    void createsCardsAndOneDraftJobInRequestOrder() throws Exception {

        MvcResult created = create(MY_REPO, ids(second, withCommit, withoutCommit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andReturn();

        String jobId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.jobId");
        List<String> cardIds = JsonPath.read(created.getResponse().getContentAsString(), "$.data.cardIds");
        assertThat(cardIds).hasSize(3);
        assertThat(cardIds).containsExactlyElementsOf(cardPublicIdsOf(second, withCommit, withoutCommit));
        assertThat(jdbc.queryForList("SELECT public_id::text, type, state, user_repository_id FROM analysis_job"))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.get("public_id")).isEqualTo(jobId);
                    assertThat(job.get("type")).isEqualTo("DRAFT");
                    assertThat(job.get("state")).isEqualTo("QUEUED");
                    assertThat(job.get("user_repository_id")).isEqualTo(myRepoId);
                });

    }

    @Test
    @DisplayName("새 카드는 이 저장소·이 후보에 묶이고 제목은 후보 제목, 출처는 AI, 기간은 비었으며 작성 중 1번 버전이고 초안 Job 을 가리킨다")
    void newCardCarriesCandidateAndJobLink() throws Exception {

        create(MY_REPO, ids(withCommit)).andExpect(status().isOk());

        Map<String, Object> card = jdbc.queryForMap(
                "SELECT c.user_id, c.user_repository_id, c.candidate_id, c.origin, c.title, c.period, c.status,"
                        + " c.current_version, c.generation_job_id::text AS job, c.generation_partial,"
                        + " (SELECT public_id::text FROM analysis_job) AS job_id FROM card c");
        assertThat(card.get("user_id")).isEqualTo(myUserId);
        assertThat(card.get("user_repository_id")).isEqualTo(myRepoId);
        assertThat(card.get("candidate_id")).isEqualTo(withCommit);
        assertThat(card.get("origin")).isEqualTo("AI");
        assertThat(card.get("title")).isEqualTo("이메일 발송");
        assertThat(card.get("period")).isNull();
        assertThat(card.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) card.get("current_version")).intValue()).isEqualTo(1);
        assertThat(card.get("job")).isEqualTo(card.get("job_id"));
        assertThat(card.get("generation_partial")).isEqualTo(false);

    }

    @Test
    @DisplayName("AI 경험 그룹화로 만든 후보로 만든 새 카드는 USER_EDIT 출처의 빈 1번 버전만 갖고 문장이 하나도 없다")
    void newCardStartsWithEmptyUserEditVersion() throws Exception {

        create(MY_REPO, ids(withCommit)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT version_no, source, is_confirmed FROM card_version"))
                .singleElement()
                .satisfies(version -> {
                    assertThat(((Number) version.get("version_no")).intValue()).isEqualTo(1);
                    assertThat(version.get("source")).isEqualTo("USER_EDIT");
                    assertThat(version.get("is_confirmed")).isEqualTo(false);
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_statement", Integer.class)).isZero();

    }

    @Test
    @DisplayName("포함된 커밋이 있는 후보는 기술 카드, 커밋이 없거나 모두 후보에서 뺀 후보는 정성 카드로 시작한다")
    void kindFollowsIncludedCommits() throws Exception {

        create(MY_REPO, ids(withCommit, withoutCommit, onlyExcludedCommit)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT card_type FROM card ORDER BY id", String.class))
                .containsExactly("TECH", "QUALITATIVE", "QUALITATIVE");

    }

    @Test
    @DisplayName("카드 종류는 후보 종류가 아니라 포함된 커밋으로만 정해져서, 커밋 묶음 후보도 커밋이 있으면 기술 카드, PR 후보도 커밋이 모두 빠졌으면 정성 카드다")
    void kindIgnoresCandidateSourceType() throws Exception {

        Long clusterWithCommit = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "5.14", "묶음", "NEW");
        candidateFixtures.insertCandidateCommit(clusterWithCommit, commitId, true);
        Long prWithoutIncluded = candidateFixtures.insertCandidate(myRepoId, "PR", 43, "#43", "빠진 PR", "NEW");
        candidateFixtures.insertCandidateCommit(prWithoutIncluded, commitId, false);

        create(MY_REPO, ids(clusterWithCommit, prWithoutIncluded)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT card_type FROM card ORDER BY id", String.class))
                .containsExactly("TECH", "QUALITATIVE");

    }

    @Test
    @DisplayName("카드로 만든 후보는 USED 가 되고, 고르지 않은 후보는 그대로 NEW 다")
    void usedCandidatesBecomeUsed() throws Exception {

        create(MY_REPO, ids(withCommit, withoutCommit)).andExpect(status().isOk());

        assertThat(candidateFixtures.statusOf(withCommit)).isEqualTo("USED");
        assertThat(candidateFixtures.statusOf(withoutCommit)).isEqualTo("USED");
        assertThat(candidateFixtures.statusOf(onlyExcludedCommit)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("같은 후보를 여러 번 보내도 한 번으로 세어 카드 한 장과 응답 카드 id 한 개만 만든다")
    void repeatedCandidateCountsOnce() throws Exception {

        create(MY_REPO, ids(withCommit, withCommit, withoutCommit, withCommit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cardIds.length()").value(2));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isEqualTo(2);

    }

    @Test
    @DisplayName("중복을 뺀 후보가 9개면 후보를 확인하기 전에 400 INVALID_REQUEST 로 거절해, 이미 쓴 후보가 섞여도 409 가 아니고 아무것도 만들지 않는다")
    void moreThanEightCandidatesAreRejected() throws Exception {

        Long used = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "5.20", "쓴 후보", "USED");
        List<UUID> nine = new ArrayList<>(ids(withCommit, withoutCommit, onlyExcludedCommit, second, used));
        for (int i = 0; i < 4; i++) {
            nine.add(candidateFixtures.publicIdOf(candidateFixtures.insertNewCandidate(myRepoId, "추가 " + i)));
        }

        create(MY_REPO, nine)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertNothingCreated();
        assertThat(candidateFixtures.statusOf(withCommit)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("같은 후보가 겹쳐 9개를 보내도 중복을 뺀 후보가 8개면 카드 8장을 만든다")
    void eightDistinctCandidatesAreAllowedEvenWithDuplicates() throws Exception {

        List<UUID> eight = new ArrayList<>(ids(withCommit, withoutCommit, onlyExcludedCommit, second));
        for (int i = 0; i < 4; i++) {
            eight.add(candidateFixtures.publicIdOf(candidateFixtures.insertNewCandidate(myRepoId, "추가 " + i)));
        }
        eight.add(eight.get(0));

        create(MY_REPO, eight)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cardIds.length()").value(8));

    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"candidateIds\":null}", "{\"candidateIds\":[]}"})
    @DisplayName("후보를 보내지 않거나 null 이거나 빈 목록이면 400 NO_CANDIDATES 이고 아무것도 만들지 않는다")
    void emptyCandidatesAreRejected(String body) throws Exception {

        mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(loggedInAs(myUserId))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NO_CANDIDATES"));

        assertNothingCreated();

    }

    @Test
    @DisplayName("본문이 아예 없어도 500 이 아니라 400 NO_CANDIDATES 다")
    void missingBodyIsRejected() throws Exception {

        mvc.perform(post("/api/repos/{id}/cards", MY_REPO).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NO_CANDIDATES"));

        assertNothingCreated();

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-a-uuid", "99999999-9999-4999-8999-999999999999"})
    @DisplayName("이 저장소의 후보가 아닌 id(null 항목·UUID 가 아닌 글자·없는 후보)가 섞이면 404 이고 고른 다른 후보도 만들지 않으며 거절 기록도 없다")
    void unknownCandidateRejectsWholeRequest(String bad) throws Exception {

        String body = "{\"candidateIds\":[\"" + candidateFixtures.publicIdOf(withCommit) + "\","
                + (bad == null ? "null" : "\"" + bad + "\"") + "]}";

        createRaw(MY_REPO.toString(), body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNothingCreated();
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("같은 사용자의 다른 저장소 후보가 섞이면 이 저장소 후보가 아니라서 404 이고, 내 후보라 거절 기록은 남기지 않는다")
    void candidateOfMyOtherRepositoryIsNotFound() throws Exception {

        Long other = candidateFixtures.insertNewCandidate(myOtherRepoId, "다른 저장소");

        create(MY_REPO, ids(withCommit, other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNothingCreated();
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("남의 저장소 후보가 섞이면 404 이고 아무것도 바꾸지 않으며, 그 후보마다 거절 기록 CREATE_CARD DENIED 한 줄을 남긴다")
    void othersCandidateIsDeniedAndRecorded() throws Exception {

        Long foreign = candidateFixtures.insertNewCandidate(othersRepoId, "남의 후보");
        Long otherForeign = candidateFixtures.insertNewCandidate(othersRepoId, "남의 다른 후보");

        create(MY_REPO, ids(withCommit, foreign, otherForeign))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNothingCreated();
        assertThat(candidateFixtures.statusOf(foreign)).isEqualTo("NEW");
        assertThat(events()).containsExactlyInAnyOrder(
                new AuditRow(myUserId, "CREATE_CARD", foreign, "DENIED"),
                new AuditRow(myUserId, "CREATE_CARD", otherForeign, "DENIED"));

    }

    @Test
    @DisplayName("남의 저장소 후보와 이미 카드로 쓴 내 후보가 한 요청에 섞이면 409 가 아니라 404 이고, 남의 후보 한 건에만 거절 기록을 남기며 아무것도 바꾸지 않는다")
    void foreignCandidateIsNotFoundBeforeUsedCandidateConflicts() throws Exception {

        Long used = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "5.20", "쓴 후보", "USED");
        Long foreign = candidateFixtures.insertNewCandidate(othersRepoId, "남의 후보");

        create(MY_REPO, ids(withCommit, used, foreign))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNothingCreated();
        assertThat(candidateFixtures.statusOf(used)).isEqualTo("USED");
        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CARD", foreign, "DENIED"));

    }

    @Test
    @DisplayName("남의 저장소에 요청하면 없는 저장소와 똑같이 404 이고, 그 저장소 연결을 대상으로 거절 기록 한 줄을 남긴다")
    void othersRepositoryIsDeniedAndRecorded() throws Exception {

        create(OTHERS_REPO, ids(withCommit))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNothingCreated();
        assertThat(events()).containsExactly(new AuditRow(myUserId, "CREATE_CARD", othersRepoId, "DENIED"));

    }

    @Test
    @DisplayName("없는 저장소나 UUID 가 아닌 저장소 id 는 404 이고 거절 기록을 남기지 않으며, 후보가 비어 있어도 저장소 검사가 먼저라 400 이 아니라 404 다")
    void unknownRepositoryIsNotFoundBeforeCandidateCheck() throws Exception {

        createRaw(UUID.randomUUID().toString(), "{\"candidateIds\":[]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        createRaw("not-a-uuid", "{\"candidateIds\":[\"" + candidateFixtures.publicIdOf(withCommit) + "\"]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNothingCreated();
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("성공한 요청은 감사 기록을 남기지 않는다")
    void successWritesNoAuditRow() throws Exception {

        create(MY_REPO, ids(withCommit)).andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"USED", "EXCLUDED"})
    @DisplayName("이미 카드로 쓴 후보나 제외한 후보가 섞이면 409 INVALID_STATE 이고 새 후보까지 포함해 카드·Job·후보 상태를 하나도 바꾸지 않는다")
    void notNewCandidateRejectsWholeRequest(String status) throws Exception {

        Long notNew = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "5.20", "쓴 후보", status);

        create(MY_REPO, ids(withCommit, notNew))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertNothingCreated();
        assertThat(candidateFixtures.statusOf(withCommit)).isEqualTo("NEW");
        assertThat(candidateFixtures.statusOf(notNew)).isEqualTo(status);

    }

    @Test
    @DisplayName("같은 후보로 요청을 두 번 보내면 두 번째는 409 이고 카드와 Job 은 하나뿐이다")
    void secondRequestForSameCandidateConflicts() throws Exception {

        create(MY_REPO, ids(withCommit)).andExpect(status().isOk());
        create(MY_REPO, ids(withCommit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(count("card")).isEqualTo(1);
        assertThat(count("analysis_job")).isEqualTo(1);

    }

    @Test
    @DisplayName("후보가 NEW 로 남아 있어도 그 후보로 살아 있는 카드가 이미 있으면 DB 유니크가 마지막으로 막아 500 이 아니라 409 이고 아무것도 바꾸지 않는다")
    void uniqueIndexIsLastGuardAndAnswers409() throws Exception {

        candidateFixtures.insertCandidateCard(myUserId, myRepoId, withCommit, UUID.randomUUID(), false);

        create(MY_REPO, ids(withoutCommit, withCommit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(count("card")).isEqualTo(1);
        assertThat(count("analysis_job")).isZero();
        assertThat(candidateFixtures.statusOf(withoutCommit)).isEqualTo("NEW");
        assertThat(candidateFixtures.statusOf(withCommit)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("같은 후보로 동시에 온 두 요청은 한쪽만 200 이고 다른 쪽은 500 이 아니라 409 이며, 카드와 Job 은 하나뿐이다")
    void simultaneousRequestsForSameCandidateCreateOne() throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            // 두 요청이 모두 같은 후보 행에서 기다리게 만든 뒤 풀어, 뒤 요청이 앞 요청이 바꾼 상태를 보고 판단하는지 확인한다
            LockHolder holder = holdCandidateLock(pool, withCommit);
            Future<MvcResult> first = pool.submit(() -> create(MY_REPO, ids(withCommit)).andReturn());
            Future<MvcResult> secondRequest = pool.submit(() -> create(MY_REPO, ids(withCommit)).andReturn());
            awaitLockWaiters(2);
            holder.release();

            List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    secondRequest.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("card")).isEqualTo(1);
        assertThat(count("analysis_job")).isEqualTo(1);

    }

    @Test
    @DisplayName("겹치는 후보를 [A, B] 와 [B, A] 순서로 동시에 보내도 서로 기다리다 멈추지 않고 한쪽만 200, 다른 쪽은 409 다")
    void overlappingRequestsInOppositeOrderDoNotDeadlock() throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            LockHolder holder = holdCandidateLock(pool, withCommit);
            Future<MvcResult> forward = pool.submit(() -> create(MY_REPO, ids(withCommit, withoutCommit)).andReturn());
            Future<MvcResult> backward = pool.submit(() -> create(MY_REPO, ids(withoutCommit, withCommit)).andReturn());
            awaitLockWaiters(2);
            holder.release();

            List<Integer> statuses = List.of(forward.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    backward.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("card")).isEqualTo(2);
        assertThat(count("analysis_job")).isEqualTo(1);

    }

    @Test
    @DisplayName("작은 id 후보를 쥔 다른 작업(다시 분석의 저장처럼 작은 id 부터 잠그는 쪽)이 다음 후보도 잠그려 해도, 요청을 큰 id 부터 보내도 작은 id 부터 잠가 교착 없이 끝난다")
    void locksCandidatesInIdOrderLikeReanalysis() throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(1);
        Long earlier = Math.min(withCommit, withoutCommit);
        Long later = Math.max(withCommit, withoutCommit);
        List<Future<MvcResult>> request = new ArrayList<>();

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM candidate WHERE id = ? FOR UPDATE", Long.class, earlier);
                request.add(pool.submit(() -> create(MY_REPO, ids(later, earlier)).andReturn()));
                lockLaterAfterRequestWaits(later);
            });

            assertThat(request.get(0).get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("card")).isEqualTo(2);

    }

    /** 요청이 보낸 순서(큰 id 먼저)대로 잠그고 있었다면, 여기서 두 번째 후보를 잠그려는 쪽과 서로를 기다려 교착이 난다 */
    private void lockLaterAfterRequestWaits(Long later) {

        awaitLockWaiters(1);
        jdbc.queryForObject("SELECT id FROM candidate WHERE id = ? FOR UPDATE", Long.class, later);

    }

    @Test
    @DisplayName("로그인하지 않은 요청은 401, CSRF 토큰이 없는 요청은 403 이고 아무것도 만들지 않는다")
    void requiresSessionAndCsrf() throws Exception {

        mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ids(withCommit)))
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ids(withCommit)))
                        .with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertNothingCreated();

    }

    private LockHolder holdCandidateLock(ExecutorService pool, Long candidateId) throws Exception {

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM candidate WHERE id = ? FOR UPDATE", Long.class, candidateId);
            locked.countDown();
            awaitQuietly(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        return new LockHolder(holder, release);

    }

    private void awaitLockWaiters(int count) {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("잠금을 기다리는 요청이 " + count + "개가 되지 않았다");

    }

    private static void awaitQuietly(CountDownLatch latch) {

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private List<UUID> ids(Long... candidateIds) {

        return Arrays.stream(candidateIds).map(candidateFixtures::publicIdOf).toList();

    }

    private static String body(List<UUID> candidateIds) {

        return "{\"candidateIds\":[" + candidateIds.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";

    }

    private ResultActions create(UUID repoId, List<UUID> candidateIds) throws Exception {

        return createRaw(repoId.toString(), body(candidateIds));

    }

    private ResultActions createRaw(String repoId, String body) throws Exception {

        return mvc.perform(post("/api/repos/{id}/cards", repoId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(loggedInAs(myUserId))
                .with(csrf()));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private List<String> cardPublicIdsOf(Long... candidateIds) {

        return Arrays.stream(candidateIds)
                .map(id -> jdbc.queryForObject("SELECT public_id::text FROM card WHERE candidate_id = ?", String.class, id))
                .toList();

    }

    private void assertNothingCreated() {

        assertThat(count("card")).isZero();
        assertThat(count("card_version")).isZero();
        assertThat(count("analysis_job")).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM candidate WHERE id IN (?, ?, ?, ?)", String.class,
                withCommit, withoutCommit, onlyExcludedCommit, second)).containsOnly("NEW");

    }

    private int count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private static final class LockHolder {

        private final Future<?> future;
        private final CountDownLatch released;

        private LockHolder(Future<?> future, CountDownLatch released) {
            this.future = future;
            this.released = released;
        }

        void release() throws Exception {

            released.countDown();
            future.get(10, TimeUnit.SECONDS);

        }
    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
