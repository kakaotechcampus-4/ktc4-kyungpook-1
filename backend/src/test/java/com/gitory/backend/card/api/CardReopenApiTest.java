package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
class CardReopenApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    // FE schemas.ts 의 Card·CardVersion 이 요구하는 키로, 값이 null 이어도 키는 빠지면 안 된다
    private static final String[] CARD_KEYS = {"id", "kind", "status", "title", "areas", "techs", "period", "repo",
            "candidate", "version", "evidence", "lowConfidenceFields", "droppedFields", "draftSteps", "maskRules", "generation",
            "draftFailure", "interviewTurns", "interviewEndReason", "confirmedAt", "createdAt"};
    private static final String[] VERSION_KEYS = {"versionNo", "source", "createdAt", "situation", "task", "action",
            "result"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long othersUserId;
    private Long myRepoId;
    private Long firstCommit;
    private Long secondCommit;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        firstCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        secondCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @Test
    @DisplayName("확정한 카드를 다시 열면 작성 중이 되고 확정 시각이 지워지며 카드 수정 시각이 바뀌지만, 새 버전은 없고 확정한 버전이 그대로 현재 버전으로 온다")
    void reopensConfirmedCardWithoutNewVersion() throws Exception {

        Long cardId = confirmedCard(MY_CARD, myUserId);
        List<Map<String, Object>> versionsBefore = versionRowsOf(cardId);
        List<Map<String, Object>> statementsBefore = statementRowsOf(cardId);

        reopen(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.version.source").value("USER_EDIT"))
                .andExpect(jsonPath("$.data.version.createdAt").value(OLD.toString()))
                .andExpect(jsonPath("$.data.version.situation").value("확정한 상황"))
                .andExpect(jsonPath("$.data.version.action").value("확정한 행동"));

        Map<String, Object> card = cardRowOf(cardId);
        assertThat(card.get("status")).isEqualTo("DRAFT");
        assertThat(card.get("confirmed_at")).isNull();
        assertThat(((Number) card.get("current_version")).intValue()).isEqualTo(2);
        assertThat(((Timestamp) card.get("updated_at")).toInstant()).isAfter(OLD);
        assertThat(versionRowsOf(cardId)).isEqualTo(versionsBefore);
        assertThat(statementRowsOf(cardId)).isEqualTo(statementsBefore);

    }

    @Test
    @DisplayName("작성 중인 카드를 다시 열면 200 으로 지금 카드가 오고 카드 수정 시각을 포함해 아무것도 바뀌지 않는다")
    void reopenOnDraftChangesNothing() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "쓰던 상황", "USER_STATED", null);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        reopen(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.version.versionNo").value(1))
                .andExpect(jsonPath("$.data.version.situation").value("쓰던 상황"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("남의 카드를 다시 열면 없는 카드와 똑같이 404 NOT_FOUND 이고 그 카드로 EDIT_CARD 거절 기록이 하나 남으며 그 카드는 확정 그대로다")
    void othersCardIsNotFoundAndRecorded() throws Exception {

        // 남의 카드 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = confirmedCard(OTHERS_CARD, othersUserId);
        List<Map<String, Object>> before = stateOf(othersCardId);

        reopen(myUserId, OTHERS_CARD.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED"));
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 를 다시 열면 404 NOT_FOUND 이고 거절 기록은 남지 않는다")
    void unknownCardIsNotFound(String id) throws Exception {

        reopen(myUserId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("확정한 카드에 복원과 다시 열기가 동시에 오면 카드 행 잠금으로 차례로 처리돼, 다시 열기는 복원 결과를 보고 둘 다 200 이며 복원한 버전이 현재 버전으로 남는다")
    void concurrentReopenSeesRestore() throws Exception {

        Long cardId = confirmedCard(MY_CARD, myUserId);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            // 복원이 먼저 카드 행에서 기다리고 다시 열기가 그 뒤에 서게 한 뒤 풀어, 다시 열기가 복원한 버전을 보는지 확인한다
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> restored = pool.submit(() -> mvc.perform(post("/api/cards/{id}/versions/{no}/restore", MY_CARD, 1)
                    .with(loggedInAs(myUserId)).with(csrf())).andReturn());
            awaitLockWaiters(1);
            Future<MvcResult> reopened = pool.submit(() -> reopen(myUserId, MY_CARD.toString()).andReturn());
            awaitLockWaiters(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            MvcResult restoreResponse = restored.get(30, TimeUnit.SECONDS);
            MvcResult reopenResponse = reopened.get(30, TimeUnit.SECONDS);
            assertThat(restoreResponse.getResponse().getStatus()).isEqualTo(200);
            assertThat(reopenResponse.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) JsonPath.read(reopenResponse.getResponse().getContentAsString(), "$.data.version.versionNo"))
                    .isEqualTo(3);
            assertThat((String) JsonPath.read(reopenResponse.getResponse().getContentAsString(), "$.data.status"))
                    .isEqualTo("DRAFT");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        Map<String, Object> card = cardRowOf(cardId);
        assertThat(card.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) card.get("current_version")).intValue()).isEqualTo(3);
        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "USER_EDIT", true),
                new VersionRow(3, "RESTORE", false));

    }

    @Test
    @DisplayName("다시 열기 응답은 FE Card 키를 모두 담고, 다시 연 결과를 카드 상세 조회와 같은 값으로 싣는다")
    void responseMatchesCardDetail() throws Exception {

        Long cardId = confirmedCard(MY_CARD, myUserId);
        jdbc.update("UPDATE card SET period = ?, user_repository_id = ?, mask_rules = ?::jsonb WHERE id = ?",
                "2024년 상반기", myRepoId, "[{\"from\": \"우리 팀\", \"to\": \"팀\"}]", cardId);
        Long result = fixtures.insertStatement(cardId, 2, "R", "커밋으로 쓴 결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, secondCommit);
        fixtures.insertStatementEvidence(result, firstCommit);

        MvcResult written = reopen(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> card = JsonPath.read(written.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
        assertThat(card.get("status")).isEqualTo("DRAFT");
        assertSameAsDetail(card);

    }

    // 1번 직접 수정 버전 위에서 고친 내용을 확정해 2번 확정본이 현재 버전인 카드로, 시각은 모두 OLD 다
    private Long confirmedCard(UUID publicId, Long userId) {

        Long cardId = fixtures.insertCard(publicId, userId);
        fixtures.insertStatement(cardId, 1, "S", "처음 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.insertStatement(cardId, 2, "S", "확정한 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "확정한 행동", "USER_STATED", null);
        fixtures.confirmCard(cardId);
        age(cardId);

        return cardId;

    }

    private ResultActions reopen(Long userId, String id) throws Exception {

        return mvc.perform(post("/api/cards/{id}/reopen", id).with(loggedInAs(userId)).with(csrf()));

    }

    @SuppressWarnings("unchecked")
    private void assertSameAsDetail(Map<String, Object> written) throws Exception {

        String body = mvc.perform(get("/api/cards/{id}", written.get("id")).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> detail = JsonPath.read(body, "$.data");

        assertThat(written).containsOnlyKeys(CARD_KEYS);
        assertThat((Map<String, Object>) written.get("version")).containsOnlyKeys(VERSION_KEYS);
        assertThat(written).isEqualTo(detail);

    }

    private void age(Long cardId) {

        OffsetDateTime old = OffsetDateTime.ofInstant(OLD, ZoneOffset.UTC);
        jdbc.update("UPDATE card SET updated_at = ? WHERE id = ?", old, cardId);
        jdbc.update("UPDATE card_version SET updated_at = ? WHERE card_id = ?", old, cardId);

    }

    private Map<String, Object> cardRowOf(Long cardId) {

        return jdbc.queryForMap(
                "SELECT status, confirmed_at, current_version, updated_at, mask_rules::text AS mask_rules FROM card WHERE id = ?",
                cardId);

    }

    private List<Map<String, Object>> versionRowsOf(Long cardId) {

        return jdbc.queryForList(
                "SELECT version_no, source, is_confirmed, updated_at FROM card_version WHERE card_id = ? ORDER BY version_no",
                cardId);

    }

    private List<Map<String, Object>> statementRowsOf(Long cardId) {

        return jdbc.queryForList("""
                SELECT s.id, s.version_no, s.star_slot, s.body, s.evidence_type, s.confidence, s.source_turn_id,
                       ARRAY(SELECT e.commit_id FROM statement_evidence e WHERE e.statement_id = s.id ORDER BY e.id)::text AS commits
                FROM card_statement s WHERE s.card_id = ? ORDER BY s.id
                """, cardId);

    }

    private List<Map<String, Object>> stateOf(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(cardRowOf(cardId));
        rows.addAll(versionRowsOf(cardId));
        rows.addAll(statementRowsOf(cardId));

        return rows;

    }

    private List<VersionRow> versionsOf(Long cardId) {

        return jdbc.query(
                "SELECT version_no, source, is_confirmed FROM card_version WHERE card_id = ? ORDER BY version_no",
                (row, rowNum) -> new VersionRow(row.getInt("version_no"), row.getString("source"),
                        row.getBoolean("is_confirmed")),
                cardId);

    }

    private void awaitLockWaiters(int count) throws InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }

        throw new AssertionError("잠금을 기다리는 요청이 " + count + "개가 되지 않았다");

    }

    private static void awaitQuietly(CountDownLatch latch) {

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }

    private record VersionRow(int versionNo, String source, boolean confirmed) {
    }
}
