package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class ActivityApiTest {

    private static final String CLUB = """
            {"name":"교내 개발 동아리","type":"CLUB","startMonth":"2025-03","endMonth":"2025-12","role":"파트장"}""";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    private TestFixtures fixtures;
    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("목록은 내 활동만 시작한 달이 최근인 순으로 주고, 활동마다 안의 카드 수를 싣는다")
    void listsMyActivitiesNewestStartFirstWithCardCount() throws Exception {

        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        fixtures.insertActivity(older, myUserId, "알고리즘 스터디", "STUDY", "2024-03-01");
        Long newerId = fixtures.insertActivity(newer, myUserId, "교내 개발 동아리", "CLUB", "2025-03-01");
        fixtures.insertActivity(UUID.randomUUID(), othersUserId, "남의 동아리", "CLUB", "2026-01-01");
        inActivity(newerId, fixtures.insertCard(UUID.randomUUID(), myUserId));
        inActivity(newerId, fixtures.insertCard(UUID.randomUUID(), myUserId));

        mvc.perform(get("/api/activities").with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.data[0].startMonth").value("2025-03"))
                .andExpect(jsonPath("$.data[0].endMonth").value(nullValue()))
                .andExpect(jsonPath("$.data[0].cardCount").value(2))
                .andExpect(jsonPath("$.data[1].id").value(older.toString()))
                .andExpect(jsonPath("$.data[1].cardCount").value(0));

    }

    @Test
    @DisplayName("활동을 만들면 보낸 값으로 저장하고, 끝난 달·역할이 빈 글이면 진행 중·역할 없음(null)으로 둔다")
    void createsActivityAndTreatsBlankEndAndRoleAsNone() throws Exception {

        String body = create(myUserId, null, CLUB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("교내 개발 동아리"))
                .andExpect(jsonPath("$.data.type").value("CLUB"))
                .andExpect(jsonPath("$.data.startMonth").value("2025-03"))
                .andExpect(jsonPath("$.data.endMonth").value("2025-12"))
                .andExpect(jsonPath("$.data.role").value("파트장"))
                .andExpect(jsonPath("$.data.cardCount").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        create(myUserId, null, """
                {"name":"해커톤","type":"CONTEST","startMonth":"2025-07","endMonth":"","role":"  "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endMonth").value(nullValue()))
                .andExpect(jsonPath("$.data.role").value(nullValue()));

        assertThat(jdbc.queryForObject("SELECT start_month::text FROM activity WHERE public_id = ?", String.class,
                UUID.fromString(JsonPath.read(body, "$.data.id")))).isEqualTo("2025-03-01");

    }

    @ParameterizedTest
    @MethodSource("invalidActivities")
    @DisplayName("이름·종류·달·기간 순서·역할이 규칙에 맞지 않으면 400 INVALID_REQUEST 이고 활동이 생기지 않는다")
    void rejectsInvalidActivity(String body) throws Exception {

        create(myUserId, null, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(activityCount()).isZero();

    }

    static Stream<Arguments> invalidActivities() {

        String fifty1 = "가".repeat(51);

        return Stream.of(
                Arguments.of(Named.of("이름 없음", """
                        {"name":null,"type":"CLUB","startMonth":"2025-03"}""")),
                Arguments.of(Named.of("이름이 공백뿐", """
                        {"name":"   ","type":"CLUB","startMonth":"2025-03"}""")),
                Arguments.of(Named.of("이름 51자", "{\"name\":\"" + fifty1 + "\",\"type\":\"CLUB\",\"startMonth\":\"2025-03\"}")),
                Arguments.of(Named.of("이름에 U+0000", """
                        {"name":"동아리\\u0000","type":"CLUB","startMonth":"2025-03"}""")),
                Arguments.of(Named.of("모르는 종류", """
                        {"name":"동아리","type":"SCHOOL","startMonth":"2025-03"}""")),
                Arguments.of(Named.of("종류 없음", """
                        {"name":"동아리","type":null,"startMonth":"2025-03"}""")),
                Arguments.of(Named.of("시작 달 없음", """
                        {"name":"동아리","type":"CLUB","startMonth":null}""")),
                Arguments.of(Named.of("달이 한 자리", """
                        {"name":"동아리","type":"CLUB","startMonth":"2025-3"}""")),
                Arguments.of(Named.of("13월", """
                        {"name":"동아리","type":"CLUB","startMonth":"2025-13"}""")),
                Arguments.of(Named.of("끝난 달이 시작한 달보다 앞섬", """
                        {"name":"동아리","type":"CLUB","startMonth":"2025-03","endMonth":"2025-02"}""")),
                Arguments.of(Named.of("역할 51자", "{\"name\":\"동아리\",\"type\":\"CLUB\",\"startMonth\":\"2025-03\",\"role\":\""
                        + fifty1 + "\"}")));

    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 다시 보내면 값이 달라도 처음 만든 활동을 돌려주고, 같은 키로 동시에 두 번 와도 활동은 하나만 생긴다")
    void sameKeyReturnsFirstActivityEvenWhenSentAtOnce() throws Exception {

        String first = idOf(create(myUserId, "key-1", CLUB).andExpect(status().isOk()));
        create(myUserId, "key-1", """
                {"name":"다른 이름","type":"STUDY","startMonth":"2024-01"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(first))
                .andExpect(jsonPath("$.data.name").value("교내 개발 동아리"));

        CountDownLatch gate = new CountDownLatch(1);
        List<CompletableFuture<String>> racing = Stream.of(1, 2)
                .map(n -> CompletableFuture.supplyAsync(() -> {
                    await(gate);
                    return idOf(perform(createRequest(myUserId, "key-2", CLUB)));
                }))
                .toList();
        gate.countDown();

        assertThat(racing.get(0).join()).isEqualTo(racing.get(1).join());
        assertThat(activityCount()).isEqualTo(2);

    }

    @Test
    @DisplayName("고치면 보낸 값만 바꾸고 null 이나 빠진 값은 그대로 두며, 끝난 달·역할은 빈 글로 지운다")
    void editsOnlySentValuesAndClearsWithEmptyText() throws Exception {

        String id = idOf(create(myUserId, null, CLUB).andExpect(status().isOk()));

        edit(myUserId, id, "{\"name\":\"연합 개발 동아리\",\"type\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("연합 개발 동아리"))
                .andExpect(jsonPath("$.data.type").value("CLUB"))
                .andExpect(jsonPath("$.data.startMonth").value("2025-03"))
                .andExpect(jsonPath("$.data.endMonth").value("2025-12"))
                .andExpect(jsonPath("$.data.role").value("파트장"));
        edit(myUserId, id, "{\"endMonth\":\"\",\"role\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("연합 개발 동아리"))
                .andExpect(jsonPath("$.data.endMonth").value(nullValue()))
                .andExpect(jsonPath("$.data.role").value(nullValue()));

    }

    @Test
    @DisplayName("바꿀 값이 없거나 고친 뒤 끝난 달이 시작한 달보다 앞서면 400 INVALID_REQUEST 이고 활동은 그대로다")
    void rejectsEditWithNothingToChangeOrReversedPeriod() throws Exception {

        String id = idOf(create(myUserId, null, CLUB).andExpect(status().isOk()));

        edit(myUserId, id, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        edit(myUserId, id, "{\"name\":null}").andExpect(status().isBadRequest());
        edit(myUserId, id, "{\"startMonth\":\"2026-01\"}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT start_month::text FROM activity WHERE public_id = ?", String.class,
                UUID.fromString(id))).isEqualTo("2025-03-01");

    }

    @Test
    @DisplayName("남의 활동을 고치거나 지우면 없는 활동과 똑같이 404 NOT_FOUND 이고, 그 활동으로 거절 기록이 남고 활동은 그대로다")
    void othersActivityIsNotFoundAndDenied() throws Exception {

        UUID others = UUID.randomUUID();
        // 활동 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 활동을 하나 더 만든다
        fixtures.insertActivity(UUID.randomUUID(), othersUserId, "다른 동아리", "CLUB", "2025-03-01");
        Long othersId = fixtures.insertActivity(others, othersUserId, "남의 동아리", "CLUB", "2025-03-01");

        edit(myUserId, others.toString(), "{\"name\":\"가로챈 이름\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        remove(myUserId, others.toString()).andExpect(status().isNotFound());
        remove(myUserId, "not-a-uuid").andExpect(status().isNotFound());

        assertThat(events()).containsExactly(
                new AuditRow(myUserId, "EDIT_ACTIVITY", othersId, "DENIED"),
                new AuditRow(myUserId, "DELETE_ACTIVITY", othersId, "DENIED"));
        assertThat(jdbc.queryForObject("SELECT name FROM activity WHERE id = ?", String.class, othersId))
                .isEqualTo("남의 동아리");

    }

    @Test
    @DisplayName("활동을 지우면 안의 카드를 버전·문장까지 함께 지우고, 다른 활동과 활동 밖 카드는 그대로 둔다")
    void deletesActivityWithOnlyItsCards() throws Exception {

        UUID club = UUID.randomUUID();
        Long clubId = fixtures.insertActivity(club, myUserId, "동아리", "CLUB", "2025-03-01");
        Long studyId = fixtures.insertActivity(UUID.randomUUID(), myUserId, "스터디", "STUDY", "2024-03-01");
        Long clubCard = inActivity(clubId, fixtures.insertCard(UUID.randomUUID(), myUserId));
        fixtures.insertStatement(clubCard, 1, "S", "행사 일정을 두고 의견이 갈렸다.", "USER_STATED", null);
        inActivity(clubId, fixtures.insertCard(UUID.randomUUID(), myUserId));
        Long studyCard = inActivity(studyId, fixtures.insertCard(UUID.randomUUID(), myUserId));
        Long outsideCard = fixtures.insertCard(UUID.randomUUID(), myUserId);

        remove(myUserId, club.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        assertThat(jdbc.queryForList("SELECT id FROM card ORDER BY id", Long.class))
                .containsExactly(studyCard, outsideCard);
        assertThat(jdbc.queryForList("SELECT DISTINCT card_id FROM card_version ORDER BY card_id", Long.class))
                .containsExactly(studyCard, outsideCard);
        assertThat(count("SELECT count(*) FROM card_statement")).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM activity", Long.class)).containsExactly(studyId);
        assertThat(events()).containsExactly(new AuditRow(myUserId, "DELETE_ACTIVITY", clubId, "OK"));

    }

    @Test
    @DisplayName("DB 도 카드가 저장소와 활동에 함께 묶이는 것을 막는다")
    void databaseKeepsCardInRepositoryOrActivityOnly() {

        Long repository = fixtures.insertUserRepository(myUserId, fixtures.insertRepository(1L, "grow22", "gitory"));
        Long activity = fixtures.insertActivity(UUID.randomUUID(), myUserId, "동아리", "CLUB", "2025-03-01");
        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);

        assertThatThrownBy(() -> jdbc.update("UPDATE card SET user_repository_id = ?, activity_id = ? WHERE id = ?",
                repository, activity, card))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("활동을 지우는 트랜잭션이 끝나기를 기다린 카드 만들기는 500 이 아니라 404 NOT_FOUND 로 끝나고 카드가 남지 않는다")
    void cardCreationWaitingOnDeletedActivityIsNotFound() throws Exception {

        UUID club = UUID.randomUUID();
        Long clubId = fixtures.insertActivity(club, myUserId, "동아리", "CLUB", "2025-03-01");
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> deleting = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM activity WHERE id = ?", clubId);
            deleted.countDown();
            await(release);
        }));
        await(deleted);
        CompletableFuture<MvcResult> creating = CompletableFuture.supplyAsync(() -> perform(post("/api/cards/manual/draft")
                .with(loggedInAs(myUserId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"카드\",\"activityId\":\"" + club + "\"}")));
        waitUntilSomeoneWaitsOnLock();
        release.countDown();
        deleting.join();

        MvcResult result = creating.join();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM card")).isZero();

    }

    private Long inActivity(Long activityId, Long cardId) {

        jdbc.update("UPDATE card SET activity_id = ? WHERE id = ?", activityId, cardId);

        return cardId;

    }

    private ResultActions create(Long userId, String idempotencyKey, String body) throws Exception {

        return mvc.perform(createRequest(userId, idempotencyKey, body));

    }

    private MockHttpServletRequestBuilder createRequest(Long userId, String idempotencyKey, String body) {

        MockHttpServletRequestBuilder request = post("/api/activities")
                .with(loggedInAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);

        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);

    }

    private ResultActions edit(Long userId, String id, String body) throws Exception {

        return mvc.perform(patch("/api/activities/{id}", id)
                .with(loggedInAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

    }

    private ResultActions remove(Long userId, String id) throws Exception {

        return mvc.perform(delete("/api/activities/{id}", id).with(loggedInAs(userId)).with(csrf()));

    }

    private MvcResult perform(MockHttpServletRequestBuilder request) {

        try {
            return mvc.perform(request).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

    }

    private static String idOf(ResultActions result) throws Exception {

        return JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");

    }

    private static String idOf(MvcResult result) {

        try {
            return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

    }

    // 카드 만들기가 지우는 트랜잭션의 행 잠금을 기다리기 시작한 뒤에 지우기를 끝내야, 잠금을 거친 길을 확인할 수 있다
    private void waitUntilSomeoneWaitsOnLock() throws InterruptedException {

        for (int tries = 0; tries < 200; tries++) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("카드 만들기가 활동 행 잠금을 기다리지 않았다");

    }

    private static void await(CountDownLatch latch) {

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("기다리던 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private int activityCount() {

        return count("SELECT count(*) FROM activity");

    }

    private int count(String sql) {

        return jdbc.queryForObject(sql, Integer.class);

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
