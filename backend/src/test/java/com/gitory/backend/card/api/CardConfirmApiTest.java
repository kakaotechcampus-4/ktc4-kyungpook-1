package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class CardConfirmApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    // 확정할 때 문항 유형이 빈 경험 카드는 AI 에 유형을 물으므로, 실제 주소 대신 받은 요청을 남기는 가짜 AI 로 받는다
    private static final FakeInterviewAi AI = FakeInterviewAi.start();

    // 화면이 확정할 때 보내는 본문이고, 서버는 읽지 않는다
    private static final String SCREEN_BODY = "{\"edited\":true,\"maskedFields\":[\"S\"]}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {

        registry.add("gitory.ai.base-url", AI::baseUrl);

    }

    @AfterAll
    static void stopAi() {

        AI.close();

    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TestFixtures fixtures;

    private Long myUserId;
    private Long othersUserId;
    private Long repositoryId;
    private Long firstCommit;
    private Long secondCommit;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        AI.reset();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        firstCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        secondCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @ParameterizedTest(name = "{0} 확정본={1}")
    @CsvSource({"USER_EDIT, false", "AI_DRAFT, false", "INTERVIEW, false", "MASK, false", "RESTORE, false",
            "USER_EDIT, true"})
    @DisplayName("확정하면 현재 버전의 출처를 그대로 쓴 확정본을 다음 번호로 만들어 네 칸 문장·확신도·답변 턴·커밋 연결을 복사하고, 이전 버전 행은 그대로 둔다")
    void confirmedVersionCopiesCurrentVersion(String source, boolean confirmed) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "처음 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, source, confirmed);
        Long turnId = fixtures.insertInterviewTurn(cardId, 1, "T");
        Long situation = fixtures.insertStatement(cardId, 2, "S", "상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, secondCommit);
        fixtures.insertStatementEvidence(situation, firstCommit);
        fixtures.insertStatement(cardId, 2, "T", "과제", "USER_SELECTED", "MEDIUM", turnId);
        fixtures.insertStatement(cardId, 2, "A", " 행동 ", "USER_STATED", null);
        Long result = fixtures.insertStatement(cardId, 2, "R", "결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, firstCommit);
        Map<String, StatementRow> first = slotsOf(cardId, 1);
        Map<String, StatementRow> current = slotsOf(cardId, 2);
        List<Long> currentIds = statementIds(cardId, 2);

        confirm(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(3));

        assertThat(versions(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, source, confirmed),
                new VersionRow(3, source, true));
        assertThat(slotsOf(cardId, 3)).isEqualTo(current);
        assertThat(slotsOf(cardId, 3).get("S").commitIds()).containsExactly(secondCommit, firstCommit);
        assertThat(slotsOf(cardId, 2)).isEqualTo(current);
        assertThat(statementIds(cardId, 2)).isEqualTo(currentIds);
        assertThat(slotsOf(cardId, 1)).isEqualTo(first);

    }

    @Test
    @DisplayName("확정하면 카드가 CONFIRMED 가 되고 확정 시각이 채워지며, 현재 버전이 확정본 번호가 되고 카드의 updatedAt 이 갱신된다")
    void confirmMarksCardConfirmed() throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        age(cardId);

        confirm(MY_CARD)
                .andExpect(status().isOk());

        Map<String, Object> card = jdbc.queryForMap(
                "SELECT status, confirmed_at, current_version FROM card WHERE id = ?", cardId);
        assertThat(card.get("status")).isEqualTo("CONFIRMED");
        assertThat(card.get("confirmed_at")).isNotNull();
        assertThat(((Number) card.get("current_version")).intValue()).isEqualTo(2);
        assertThat(cardUpdatedAt(cardId)).isAfter(OLD);
        assertThat(versions(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "USER_EDIT", true));

    }

    @Test
    @DisplayName("확정 응답은 카드 공개 id·CONFIRMED·확정본 번호·빈 후보 목록·남은 후보 0 과, 저장소가 없으면 null 인 repoId 까지 키 6개를 모두 담는다")
    void responseHasEveryContractKey() throws Exception {

        manualCardWithSituationAndAction();

        MvcResult confirmed = confirm(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andReturn();

        Map<String, Object> data = JsonPath.read(confirmed.getResponse().getContentAsString(), "$.data");
        assertThat(data).containsOnlyKeys("cardId", "status", "versionNo", "usedCandidateIds", "remainingCandidates",
                "repoId");
        assertThat(data.get("cardId")).isEqualTo(MY_CARD.toString());
        assertThat(data.get("status")).isEqualTo("CONFIRMED");
        assertThat(data.get("versionNo")).isEqualTo(2);
        assertThat((List<?>) data.get("usedCandidateIds")).isEmpty();
        assertThat(data.get("remainingCandidates")).isEqualTo(0);
        assertThat(data.get("repoId")).isNull();

    }

    @Test
    @DisplayName("저장소가 붙은 카드를 확정하면 repoId 는 그 저장소 연결의 공개 id 다")
    void responseCarriesRepoIdOfCard() throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        Long connectionId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", connectionId, cardId);

        confirm(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repoId").value(MY_REPO.toString()));

    }

    @Test
    @DisplayName("확정에 성공하면 요청자·CONFIRM_CARD·그 카드 내부 id 로 OK 기록이 한 건 남고, 422 로 실패한 확정은 기록을 남기지 않는다")
    void recordsOkOnlyWhenConfirmed() throws Exception {

        UUID emptyCard = UUID.randomUUID();
        fixtures.insertCard(emptyCard, myUserId);
        Long cardId = manualCardWithSituationAndAction();

        confirm(emptyCard)
                .andExpect(status().is(422));
        assertThat(events()).isEmpty();

        confirm(MY_CARD)
                .andExpect(status().isOk());
        assertThat(events()).containsExactly(new AuditRow(myUserId, "CONFIRM_CARD", cardId, "OK"));

    }

    @Test
    @DisplayName("이미 확정된 카드에 다시 확정하면 200 과 같은 확정 결과를 돌려주고, 새 버전·상태·시각이 그대로이며 OK 기록도 더 남지 않는다")
    void reconfirmReturnsSameResult() throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        String first = confirm(MY_CARD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> before = stateOf(cardId);

        String second = confirm(MY_CARD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> firstData = JsonPath.read(first, "$.data");
        Map<String, Object> secondData = JsonPath.read(second, "$.data");
        assertThat(secondData).isEqualTo(firstData);
        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(events()).containsExactly(new AuditRow(myUserId, "CONFIRM_CARD", cardId, "OK"));

    }

    @Test
    @DisplayName("이미 확정된 카드는 현재 버전에 상황·행동이 비어 있어도 422 가 아니라 200 과 지금 확정 결과를 돌려주고 아무것도 바꾸지 않는다")
    void reconfirmSkipsConfirmableCheck() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(cardId);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        confirm(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.versionNo").value(2));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(events()).isEmpty();

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("slotsWithoutSituationOrAction")
    @DisplayName("현재 버전에 상황(S)이나 행동(A) 문장이 없으면 422 NOT_CONFIRMABLE 이고 카드·버전·문장·시각이 그대로다")
    void rejectsWithoutSituationOrAction(List<String> filled) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        for (String slot : filled) {
            fixtures.insertStatement(cardId, 1, slot, slot + " 칸 글", "USER_STATED", null);
        }
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        confirm(MY_CARD)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_CONFIRMABLE"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("과제(T)·결과(R)가 비어 있어도 상황(S)과 행동(A)이 있으면 확정되고 확정본에는 S·A 두 칸만 있다")
    void confirmsWithoutTaskAndResult() throws Exception {

        Long cardId = manualCardWithSituationAndAction();

        confirm(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(slotsOf(cardId, 2)).containsOnlyKeys("S", "A");

    }

    @Test
    @DisplayName("확인 필요(confidence LOW)로 표시된 상황·행동도 글이 있으면 채운 칸으로 보고 확정하며, 확정본에도 LOW 가 그대로 남는다")
    void lowConfidenceSlotCountsAsFilled() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        jdbc.update("UPDATE card SET origin = 'AI' WHERE id = ?", cardId);
        jdbc.update("UPDATE card_version SET source = 'AI_DRAFT' WHERE card_id = ?", cardId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(situation, firstCommit);
        Long action = fixtures.insertStatement(cardId, 1, "A", "AI 행동", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(action, secondCommit);

        confirm(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2));

        assertThat(slotsOf(cardId, 2).get("S").confidence()).isEqualTo("LOW");
        assertThat(slotsOf(cardId, 2).get("A").confidence()).isEqualTo("LOW");

    }

    @Test
    @DisplayName("남의 카드를 확정하면 없는 카드와 똑같이 404 이고, 요청자·CONFIRM_CARD·그 카드 내부 id 로 거절 기록을 남기며 그 카드는 그대로다")
    void othersCardIsNotFoundAndRecorded() throws Exception {

        // 남의 카드 id 가 내 사용자 id 와 같아지면 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 먼저 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        fixtures.insertStatement(othersCardId, 1, "S", "남의 상황", "USER_STATED", null);
        fixtures.insertStatement(othersCardId, 1, "A", "남의 행동", "USER_STATED", null);
        List<Map<String, Object>> before = stateOf(othersCardId);

        confirm(OTHERS_CARD)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "CONFIRM_CARD", othersCardId, "DENIED"));
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("없는 카드나 UUID 가 아닌 id 를 확정하면 404 이고 거절 기록을 남기지 않는다")
    void unknownOrMalformedCardIsNotFoundWithoutRecord() throws Exception {

        confirm(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(confirmRequest("not-a-uuid").with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("로그인하지 않은 확정은 401, CSRF 토큰이 없는 확정은 403 이고 카드는 그대로다")
    void confirmNeedsSessionAndCsrf() throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        List<Map<String, Object>> before = stateOf(cardId);

        mvc.perform(confirmRequest(MY_CARD.toString()).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(confirmRequest(MY_CARD.toString()).with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("본문 없이 확정을 보내도 200 이고 확정된다")
    void confirmsWithoutBody() throws Exception {

        Long cardId = manualCardWithSituationAndAction();

        mvc.perform(post("/api/cards/{id}/confirm", MY_CARD).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.versionNo").value(2));

        assertThat(versions(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "USER_EDIT", true));

    }

    @Test
    @DisplayName("같은 카드에 확정 두 건이 동시에 오면 카드 행 잠금으로 차례로 처리돼 둘 다 200 과 같은 확정본 번호를 받고 확정본은 하나만 생긴다")
    void concurrentConfirmsCreateOneConfirmedVersion() throws Exception {

        Long cardId = manualCardWithSituationAndAction();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            // 두 요청이 모두 같은 카드 행에서 기다리게 만든 뒤 풀어, 뒤 요청이 앞 요청의 확정을 보고 판단하는지 확인한다
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> first = pool.submit(() -> confirm(MY_CARD).andReturn());
            Future<MvcResult> second = pool.submit(() -> confirm(MY_CARD).andReturn());
            awaitLockWaiters(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            for (MvcResult response : List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))) {
                assertThat(response.getResponse().getStatus()).isEqualTo(200);
                assertThat((Integer) JsonPath.read(response.getResponse().getContentAsString(), "$.data.versionNo"))
                        .isEqualTo(2);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(versions(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "USER_EDIT", true));
        assertThat(events()).containsExactly(new AuditRow(myUserId, "CONFIRM_CARD", cardId, "OK"));

    }

    static Stream<Arguments> slotsWithoutSituationOrAction() {

        return Stream.of(
                Arguments.of(Named.of("S·T·R 만 있음", List.of("S", "T", "R"))),
                Arguments.of(Named.of("T·A·R 만 있음", List.of("T", "A", "R"))),
                Arguments.of(Named.of("T·R 만 있음", List.of("T", "R"))),
                Arguments.of(Named.of("빈 카드", List.of())));

    }

    @Test
    @DisplayName("문항 유형이 빈 경험 카드를 확정하면 확정을 저장한 뒤 AI 에 제목과 네 칸 문장을 보내 고른 유형을 붙이고, 모르는 값·겹친 값은 버린다")
    void asksAiForThemesOfAnExperienceCard() throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        AI.replyWith(request -> themesReply(request, "COLLABORATION", "SINGING", "COLLABORATION", "GROWTH"));

        confirm(MY_CARD).andExpect(status().isOk());

        assertThat(themesAfterSuggestion(cardId)).isEqualTo("COLLABORATION,GROWTH");
        assertThat(AI.requests()).hasSize(1);
        JsonNode sent = AI.requests().get(0);
        assertThat(sent.path("card_id").longValue()).isEqualTo(cardId);
        assertThat(sent.path("title").asString()).isEqualTo("카드");
        assertThat(sent.path("slots").toString()).isEqualTo(
                "[{\"star_slot\":\"S\",\"body\":\"상황\"},{\"star_slot\":\"T\",\"body\":null},"
                        + "{\"star_slot\":\"A\",\"body\":\"행동\"},{\"star_slot\":\"R\",\"body\":null}]");

    }

    @Test
    @DisplayName("확정 버튼 없이 네 칸을 저장해 저절로 확정된 경험 카드도 저장 뒤 AI 에게 유형을 물어 붙인다")
    void asksAiForThemesWhenSavingConfirmsTheCard() throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        AI.replyWith(request -> themesReply(request, "GROWTH"));

        mvc.perform(patch("/api/cards/{id}/draft", MY_CARD.toString())
                        .with(loggedInAs(myUserId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"situation\":\"상황\",\"task\":\"맡은 일\",\"action\":\"행동\",\"result\":\"결과\"}"))
                .andExpect(status().isOk());

        assertThat(themesAfterSuggestion(cardId)).isEqualTo("GROWTH");
        assertThat(AI.requests()).singleElement()
                .satisfies(sent -> assertThat(sent.path("slots").toString()).contains("\"body\":\"결과\""));

    }

    @ParameterizedTest
    @MethodSource("unusableThemeReplies")
    @DisplayName("AI 가 유형을 주지 못하거나 다른 카드의 답을 주면 확정은 그대로 하고 유형은 비워 둔다")
    void confirmsWithoutThemesWhenAiFails(Function<JsonNode, FakeInterviewAi.Reply> reply) throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        AI.replyWith(reply);

        confirm(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        awaitAiRequests(1);
        assertThat(themesOf(cardId)).isEmpty();

    }

    @ParameterizedTest
    @MethodSource("cardsNotNeedingThemes")
    @DisplayName("사용자가 유형을 고른 카드·기술 카드·이미 확정한 카드는 AI 에 유형을 묻지 않는다")
    void doesNotAskAiWhenThemesAreNotNeeded(String update) throws Exception {

        Long cardId = manualCardWithSituationAndAction();
        jdbc.update(update, cardId);

        confirm(MY_CARD).andExpect(status().isOk());

        assertThat(AI.requests()).isEmpty();

    }

    @Test
    @DisplayName("상황·행동이 비어 확정할 수 없는 카드는 AI 에 유형을 묻지 않고 422 NOT_CONFIRMABLE 이다")
    void unconfirmableCardDoesNotAskAi() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        confirm(MY_CARD)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("NOT_CONFIRMABLE"));

        assertThat(AI.requests()).isEmpty();

    }

    static Stream<Arguments> unusableThemeReplies() {

        Function<JsonNode, FakeInterviewAi.Reply> failure = request -> FakeInterviewAi.failure(503, "LLM_UNAVAILABLE", true);
        Function<JsonNode, FakeInterviewAi.Reply> otherCard = request -> FakeInterviewAi.success(
                JsonMapper.builder().build().createObjectNode().put("card_id", 999_999).set("themes",
                        JsonMapper.builder().build().createArrayNode().add("GROWTH")));

        return Stream.of(
                Arguments.of(Named.of("AI 오류", failure)),
                Arguments.of(Named.of("다른 카드 id", otherCard)));

    }

    static Stream<Arguments> cardsNotNeedingThemes() {

        return Stream.of(
                Arguments.of(Named.of("사용자가 고른 빈 유형", "UPDATE card SET themes_by_user = true WHERE id = ?")),
                Arguments.of(Named.of("기술 카드", "UPDATE card SET card_type = 'TECH' WHERE id = ?")),
                Arguments.of(Named.of("이미 확정한 카드",
                        "UPDATE card SET status = 'CONFIRMED', confirmed_at = now() WHERE id = ?")));

    }

    private static FakeInterviewAi.Reply themesReply(JsonNode request, String... themes) {

        ObjectNode data = JsonMapper.builder().build().createObjectNode().put("card_id", request.path("card_id").longValue());
        for (String theme : themes) {
            data.withArray("themes").add(theme);
        }

        return FakeInterviewAi.success(data);

    }

    private String themesOf(Long cardId) {

        return jdbc.queryForObject("SELECT array_to_string(themes, ',') FROM card WHERE id = ?", String.class, cardId);

    }

    // 유형은 확정을 저장한 뒤 따로 붙으므로 붙을 때까지 잠깐 기다려 읽는다
    private String themesAfterSuggestion(Long cardId) throws InterruptedException {

        for (int i = 0; i < 100 && themesOf(cardId).isEmpty(); i++) {
            Thread.sleep(50);
        }

        return themesOf(cardId);

    }

    private void awaitAiRequests(int count) throws InterruptedException {

        for (int i = 0; i < 100 && AI.requests().size() < count; i++) {
            Thread.sleep(50);
        }
        assertThat(AI.requests()).hasSizeGreaterThanOrEqualTo(count);

    }

    private Long manualCardWithSituationAndAction() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "A", "행동", "USER_STATED", null);

        return cardId;

    }

    private void age(Long cardId) {

        OffsetDateTime old = OffsetDateTime.ofInstant(OLD, ZoneOffset.UTC);
        jdbc.update("UPDATE card SET updated_at = ? WHERE id = ?", old, cardId);
        jdbc.update("UPDATE card_version SET updated_at = ? WHERE card_id = ?", old, cardId);

    }

    private ResultActions confirm(UUID publicId) throws Exception {

        return mvc.perform(confirmRequest(publicId.toString()).with(loggedInAs(myUserId)).with(csrf()));

    }

    private MockHttpServletRequestBuilder confirmRequest(String id) {

        return post("/api/cards/{id}/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(SCREEN_BODY);

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

    private List<Map<String, Object>> stateOf(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList(
                "SELECT current_version, status, confirmed_at, updated_at FROM card WHERE id = ?", cardId));
        rows.addAll(jdbc.queryForList(
                "SELECT version_no, source, is_confirmed, updated_at FROM card_version WHERE card_id = ? ORDER BY version_no",
                cardId));
        rows.addAll(jdbc.queryForList(
                "SELECT id, version_no, star_slot, body, evidence_type, confidence, source_turn_id FROM card_statement WHERE card_id = ? ORDER BY id",
                cardId));
        rows.addAll(jdbc.queryForList(
                "SELECT e.id, e.statement_id, e.commit_id FROM statement_evidence e JOIN card_statement s ON s.id = e.statement_id WHERE s.card_id = ? ORDER BY e.id",
                cardId));

        return rows;

    }

    private List<VersionRow> versions(Long cardId) {

        return jdbc.query("SELECT version_no, source, is_confirmed FROM card_version WHERE card_id = ? ORDER BY version_no",
                (row, rowNum) -> new VersionRow(row.getInt("version_no"), row.getString("source"),
                        row.getBoolean("is_confirmed")),
                cardId);

    }

    private Map<String, StatementRow> slotsOf(Long cardId, int versionNo) {

        Map<String, StatementRow> slots = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, star_slot, body, evidence_type, confidence, source_turn_id FROM card_statement WHERE card_id = ? AND version_no = ? ORDER BY id",
                cardId, versionNo)) {
            List<Long> commitIds = jdbc.queryForList(
                    "SELECT commit_id FROM statement_evidence WHERE statement_id = ? ORDER BY id", Long.class, row.get("id"));
            slots.put(((String) row.get("star_slot")).trim(), new StatementRow((String) row.get("body"),
                    (String) row.get("evidence_type"), (String) row.get("confidence"), (Long) row.get("source_turn_id"),
                    commitIds));
        }

        return slots;

    }

    private List<Long> statementIds(Long cardId, int versionNo) {

        return jdbc.queryForList("SELECT id FROM card_statement WHERE card_id = ? AND version_no = ? ORDER BY id",
                Long.class, cardId, versionNo);

    }

    private Instant cardUpdatedAt(Long cardId) {

        return jdbc.queryForObject("SELECT updated_at FROM card WHERE id = ?", OffsetDateTime.class, cardId).toInstant();

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private record VersionRow(int versionNo, String source, boolean confirmed) {
    }

    private record StatementRow(String body, String evidenceType, String confidence, Long sourceTurnId,
                                List<Long> commitIds) {
    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
