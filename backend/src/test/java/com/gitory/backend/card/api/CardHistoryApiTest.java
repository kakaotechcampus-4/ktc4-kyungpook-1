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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.hamcrest.Matchers.hasSize;
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
class CardHistoryApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    // FE schemas.ts 의 Card·CardVersion·VersionListItem 이 요구하는 키로, 값이 null 이어도 키는 빠지면 안 된다
    private static final String[] CARD_KEYS = {"id", "kind", "status", "title", "areas", "techs", "themes", "period", "repo",
            "candidate", "version", "evidence", "lowConfidenceFields", "droppedFields", "draftSteps", "maskRules", "generation",
            "draftFailure", "interviewTurns", "interviewEndReason", "confirmedAt", "createdAt"};
    private static final String[] VERSION_KEYS = {"versionNo", "source", "createdAt", "situation", "task", "action",
            "result"};
    private static final String[] VERSION_ITEM_KEYS = {"versionNo", "source", "createdAt", "summary", "current"};

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
    @DisplayName("작성 이력은 최신 버전부터 버전 번호·출처·그 버전의 마지막 저장 시각·요약·현재 여부를 싣고, 현재 버전만 current 가 true 다")
    void listsVersionsNewestFirst() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertCardVersion(cardId, 3, "MASK", false);
        // 버전 번호 순서와 시각 순서를 다르게 두고, 카드 시각도 따로 둬 무엇으로 정렬하고 어느 시각을 싣는지 가린다
        versionSavedAt(cardId, 1, "2026-03-03T03:03:03Z");
        versionSavedAt(cardId, 2, "2026-01-01T01:01:01Z");
        versionSavedAt(cardId, 3, "2026-02-02T02:02:02Z");
        jdbc.update("UPDATE card SET updated_at = '2026-09-09T09:09:09Z' WHERE id = ?", cardId);

        String body = versions(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> items = JsonPath.read(body, "$.data");
        assertThat(items).allSatisfy(item -> assertThat(item).containsOnlyKeys(VERSION_ITEM_KEYS));
        assertThat(items).extracting(item -> item.get("versionNo"), item -> item.get("source"),
                        item -> item.get("createdAt"), item -> item.get("current"))
                .containsExactly(
                        tuple(3, "MASK", "2026-02-02T02:02:02Z", true),
                        tuple(2, "USER_EDIT", "2026-01-01T01:01:01Z", false),
                        tuple(1, "AI_DRAFT", "2026-03-03T03:03:03Z", false));

    }

    @Test
    @DisplayName("요약은 그 버전에서 채운 칸을 S·T·A·R 순서의 글자로 ' · ' 로 잇고 ' 채움' 을 붙이며, 채운 칸이 없으면 '채운 칸 없음' 이고 출처·시각은 넣지 않는다")
    void summarizesFilledSlots() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "A", "행동", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "S", "상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 3, "INTERVIEW", false);
        fixtures.insertStatement(cardId, 3, "R", "결과", "USER_STATED", null);
        fixtures.insertStatement(cardId, 3, "A", "행동", "USER_STATED", null);
        fixtures.insertStatement(cardId, 3, "T", "과제", "USER_STATED", null);
        fixtures.insertStatement(cardId, 3, "S", "상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 4, "MASK", false);
        fixtures.insertStatement(cardId, 4, "T", "과제", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 5, "RESTORE", false);
        fixtures.insertStatement(cardId, 5, "R", "결과", "USER_STATED", null);
        fixtures.insertStatement(cardId, 5, "A", "행동", "USER_STATED", null);

        assertThat(summariesOf(MY_CARD)).containsExactly(
                "A · R 채움",
                "T 채움",
                "S · T · A · R 채움",
                "S · A 채움",
                "채운 칸 없음");

    }

    @Test
    @DisplayName("' · 원본(잠김)' 은 카드의 가장 앞 AI 초안 버전에만 붙고, AI 초안 출처를 이어받은 뒤 버전(확정본 포함)에는 붙지 않는다")
    void marksOnlyFirstAiDraftAsOriginal() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "S", "고친 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "쓴 행동", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 3, "AI_DRAFT", false);
        fixtures.insertStatement(cardId, 3, "S", "다시 만든 상황", "COMMIT", "HIGH");
        fixtures.insertCardVersion(cardId, 4, "AI_DRAFT", true);
        fixtures.insertStatement(cardId, 4, "S", "다시 만든 상황", "COMMIT", "HIGH");
        fixtures.confirmCard(cardId);

        assertThat(summariesOf(MY_CARD)).containsExactly(
                "S 채움 · 확정",
                "S 채움",
                "S · A 채움",
                "S 채움 · 원본(잠김)");

    }

    @Test
    @DisplayName("1번 버전이 AI 초안이 아니어도 ' · 원본(잠김)' 은 번호가 가장 작은 AI 초안 버전 하나에만 붙는다")
    void marksLowestAiDraftEvenWhenNotFirstVersion() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "쓴 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "AI_DRAFT", false);
        fixtures.insertStatement(cardId, 2, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertCardVersion(cardId, 3, "AI_DRAFT", false);
        fixtures.insertStatement(cardId, 3, "S", "다시 만든 상황", "COMMIT", "HIGH");

        assertThat(summariesOf(MY_CARD)).containsExactly(
                "S 채움",
                "S 채움 · 원본(잠김)",
                "S 채움");

    }

    @Test
    @DisplayName("확정하며 만든 버전에는 카드가 지금 확정 상태인지와 상관없이 모두 ' · 확정' 이 붙는다")
    void marksEveryConfirmedVersion() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "A", "행동", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.insertStatement(cardId, 2, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "행동", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 3, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 3, "S", "다시 쓴 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 4, "RESTORE", true);
        fixtures.insertStatement(cardId, 4, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 4, "A", "행동", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 5, "USER_EDIT", false);

        assertThat(summariesOf(MY_CARD)).containsExactly(
                "채운 칸 없음",
                "S · A 채움 · 확정",
                "S 채움",
                "S · A 채움 · 확정",
                "S · A 채움");

    }

    @Test
    @DisplayName("남의 카드 작성 이력을 보면 없는 카드와 똑같이 404 NOT_FOUND 이고 그 카드로 VIEW_CARD 거절 기록이 하나 남는다")
    void othersHistoryIsNotFoundAndRecorded() throws Exception {

        // 남의 카드 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);

        versions(myUserId, OTHERS_CARD.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_CARD", othersCardId, "DENIED"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 의 작성 이력은 404 NOT_FOUND 이고 거절 기록은 남지 않는다")
    void unknownCardHistoryIsNotFound(String id) throws Exception {

        versions(myUserId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("예전 버전 내용을 보면 그 버전의 번호·출처·마지막 저장 시각과 네 칸 글(빈 칸은 null)을 카드 상세 version 과 같은 키로 싣고, 가리기 규칙이 있어도 원문 그대로이며 카드는 바뀌지 않는다")
    void showsChosenVersionAsWritten() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "S", "우리 팀 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "고친 행동", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 3, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 3, "S", "지금 상황", "USER_STATED", null);
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?", "[{\"from\": \"우리 팀\", \"to\": \"팀\"}]", cardId);
        age(cardId);
        versionSavedAt(cardId, 2, "2026-02-02T02:02:02Z");
        List<Map<String, Object>> before = stateOf(cardId);

        String body = versionOf(myUserId, MY_CARD.toString(), "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> version = JsonPath.read(body, "$.data");
        assertThat(version).containsOnlyKeys(VERSION_KEYS);
        assertThat(version)
                .containsEntry("versionNo", 2)
                .containsEntry("source", "USER_EDIT")
                .containsEntry("createdAt", "2026-02-02T02:02:02Z")
                .containsEntry("situation", "우리 팀 상황")
                .containsEntry("task", null)
                .containsEntry("action", "고친 행동")
                .containsEntry("result", null);
        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("지금 버전 번호를 골라도 막지 않고, 카드 상세 조회의 version 과 같은 값을 돌려준다")
    void currentVersionMatchesCardDetail() throws Exception {

        confirmedCard();

        String body = versionOf(myUserId, MY_CARD.toString(), "2")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String detail = mvc.perform(get("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> version = JsonPath.read(body, "$.data");
        Map<String, Object> detailVersion = JsonPath.read(detail, "$.data.version");
        assertThat(version).isEqualTo(detailVersion);

    }

    @Test
    @DisplayName("남의 카드 버전을 보면 그 번호의 버전이 있어도 없는 카드와 똑같이 404 NOT_FOUND 이고 그 카드로 VIEW_CARD 거절 기록이 하나 남는다")
    void othersVersionIsNotFoundAndRecorded() throws Exception {

        // 남의 카드 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);

        versionOf(myUserId, OTHERS_CARD.toString(), "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_CARD", othersCardId, "DENIED"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 의 버전을 보면 404 NOT_FOUND 이고 거절 기록은 남지 않는다")
    void unknownCardVersionIsNotFound(String id) throws Exception {

        versionOf(myUserId, id, "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3, 32768, 65537})
    @DisplayName("없는 버전 번호(0·음수·없는 번호·32768 이상)를 보면 404 NOT_FOUND 이고, short 로 바꾸면 1이 되는 65537 도 1번 버전을 돌려주지 않는다")
    void unknownVersionNoIsNotFound(int versionNo) throws Exception {

        confirmedCard();

        versionOf(myUserId, MY_CARD.toString(), String.valueOf(versionNo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5"})
    @DisplayName("버전 번호가 정수가 아니면 400 INVALID_REQUEST 이다")
    void nonIntegerVersionNoIsBadRequestOnView(String versionNo) throws Exception {

        confirmedCard();

        versionOf(myUserId, MY_CARD.toString(), versionNo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

    }

    @Test
    @DisplayName("작성 중 카드에서 예전 버전을 고르면 그 버전의 네 칸(빈 칸은 빈 채로)과 근거 커밋 연결을 복사한 RESTORE 버전이 현재 번호+1 로 생겨 현재 버전이 되고, 예전 버전들은 그대로이며 카드 수정 시각이 바뀐다")
    void restoresChosenVersionAsNewVersion() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        Long aiSituation = fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(aiSituation, secondCommit);
        fixtures.insertStatementEvidence(aiSituation, firstCommit);
        fixtures.insertCardVersion(cardId, 2, "INTERVIEW", false);
        Long situation = fixtures.insertStatement(cardId, 2, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, secondCommit);
        fixtures.insertStatementEvidence(situation, firstCommit);
        Long turn = fixtures.insertInterviewTurn(cardId, 1, "T");
        fixtures.insertStatement(cardId, 2, "T", "고른 답", "USER_SELECTED", null, turn);
        Long action = fixtures.insertStatement(cardId, 2, "A", "흐린 행동", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(action, firstCommit);
        fixtures.insertCardVersion(cardId, 3, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 3, "S", "다시 쓴 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 3, "T", "다시 쓴 과제", "USER_STATED", null);
        fixtures.insertStatement(cardId, 3, "A", "다시 쓴 행동", "USER_STATED", null);
        fixtures.insertStatement(cardId, 3, "R", "새로 쓴 결과", "USER_STATED", null);
        age(cardId);
        List<Map<String, Object>> olderBefore = rowsOfVersionsUpTo(cardId, 3);

        restore(myUserId, MY_CARD.toString(), "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version.versionNo").value(4))
                .andExpect(jsonPath("$.data.version.source").value("RESTORE"))
                .andExpect(jsonPath("$.data.version.situation").value("AI 상황"))
                .andExpect(jsonPath("$.data.version.task").value("고른 답"))
                .andExpect(jsonPath("$.data.version.action").value("흐린 행동"))
                .andExpect(jsonPath("$.data.version.result").value(nullValue()))
                .andExpect(jsonPath("$.data.evidence", hasSize(4)))
                .andExpect(jsonPath("$.data.lowConfidenceFields", hasSize(1)))
                .andExpect(jsonPath("$.data.lowConfidenceFields[0].field").value("A"));

        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "AI_DRAFT", false),
                new VersionRow(2, "INTERVIEW", false),
                new VersionRow(3, "USER_EDIT", false),
                new VersionRow(4, "RESTORE", false));
        assertThat(slotsOf(cardId, 4)).containsExactly(
                new SlotRow("A", "흐린 행동", "COMMIT", "LOW", null, List.of(firstCommit)),
                new SlotRow("S", "AI 상황", "COMMIT", "HIGH", null, List.of(secondCommit, firstCommit)),
                new SlotRow("T", "고른 답", "USER_SELECTED", null, turn, List.of()));
        assertThat(rowsOfVersionsUpTo(cardId, 3)).isEqualTo(olderBefore);
        Map<String, Object> card = cardRowOf(cardId);
        assertThat(((Number) card.get("current_version")).intValue()).isEqualTo(4);
        assertThat(((Timestamp) card.get("updated_at")).toInstant()).isAfter(OLD);

    }

    @Test
    @DisplayName("확정한 카드에서 복원하면 한 요청 안에서 다시 열려(작성 중, 확정 시각 없음) 복원 버전이 현재 버전이 되고, 원래 확정본은 확정본 표시를 그대로 가진다")
    void restoreOnConfirmedCardReopens() throws Exception {

        Long cardId = confirmedCard();

        restore(myUserId, MY_CARD.toString(), "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.version.versionNo").value(3))
                .andExpect(jsonPath("$.data.version.source").value("RESTORE"))
                .andExpect(jsonPath("$.data.version.situation").value("처음 상황"))
                .andExpect(jsonPath("$.data.version.action").value(nullValue()));

        Map<String, Object> card = cardRowOf(cardId);
        assertThat(card.get("status")).isEqualTo("DRAFT");
        assertThat(card.get("confirmed_at")).isNull();
        assertThat(((Number) card.get("current_version")).intValue()).isEqualTo(3);
        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "USER_EDIT", true),
                new VersionRow(3, "RESTORE", false));

    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3, 32768, 65537, 65538, -65535, Integer.MAX_VALUE})
    @DisplayName("없는 버전 번호(0·음수·없는 번호·32768 이상)로 복원하면 404 NOT_FOUND 이고, 확정한 카드도 확정 그대로 버전 수도 그대로다")
    void unknownVersionIsNotFound(int versionNo) throws Exception {

        Long cardId = confirmedCard();
        List<Map<String, Object>> before = stateOf(cardId);

        restore(myUserId, MY_CARD.toString(), String.valueOf(versionNo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(cardRowOf(cardId).get("status")).isEqualTo("CONFIRMED");

    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5"})
    @DisplayName("버전 번호가 정수가 아니면 400 INVALID_REQUEST 이고 카드는 그대로다")
    void nonIntegerVersionIsBadRequest(String versionNo) throws Exception {

        Long cardId = confirmedCard();
        List<Map<String, Object>> before = stateOf(cardId);

        restore(myUserId, MY_CARD.toString(), versionNo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("지금 버전을 골라 복원하면 새 버전을 만들지 않고 확정한 카드도 다시 열지 않아 카드 수정 시각까지 아무것도 바뀌지 않으며, 200 으로 지금 카드를 돌려준다")
    void restoringCurrentVersionChangesNothing(boolean confirmed) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "처음 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", confirmed);
        fixtures.insertStatement(cardId, 2, "S", "지금 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "지금 행동", "USER_STATED", null);
        if (confirmed) {
            fixtures.confirmCard(cardId);
        }
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        MvcResult restored = restore(myUserId, MY_CARD.toString(), "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value(confirmed ? "CONFIRMED" : "DRAFT"))
                .andExpect(jsonPath("$.data.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.version.source").value("USER_EDIT"))
                .andExpect(jsonPath("$.data.version.situation").value("지금 상황"))
                .andExpect(jsonPath("$.data.version.action").value("지금 행동"))
                .andReturn();

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertSameAsDetail(JsonPath.read(restored.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2"})
    @DisplayName("남의 카드를 복원하면 고른 번호가 그 카드의 예전 버전이든 지금 버전이든 없는 카드와 똑같이 404 NOT_FOUND 이고 그 카드로 EDIT_CARD 거절 기록이 하나 남으며 그 카드는 그대로다")
    void othersCardRestoreIsNotFoundAndRecorded(String versionNo) throws Exception {

        // 남의 카드 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        fixtures.insertStatement(othersCardId, 1, "S", "남의 상황", "USER_STATED", null);
        fixtures.insertCardVersion(othersCardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(othersCardId);
        List<Map<String, Object>> before = stateOf(othersCardId);

        restore(myUserId, OTHERS_CARD.toString(), versionNo)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED"));
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 를 복원하면 404 NOT_FOUND 이고 거절 기록은 남지 않는다")
    void unknownCardRestoreIsNotFound(String id) throws Exception {

        restore(myUserId, id, "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("같은 카드에 복원 두 건이 동시에 오면 카드 행 잠금으로 차례로 처리돼 둘 다 200 이고 서로 다른 번호의 RESTORE 버전이 둘 생긴다")
    void concurrentRestoresCreateDistinctVersions() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "처음 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "S", "지금 상황", "USER_STATED", null);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Integer> versionNos = new ArrayList<>();

        try {
            // 두 요청이 모두 같은 카드 행에서 기다리게 만든 뒤 풀어, 뒤 요청이 앞 요청이 만든 번호를 보는지 확인한다
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> first = pool.submit(() -> restore(myUserId, MY_CARD.toString(), "1").andReturn());
            Future<MvcResult> second = pool.submit(() -> restore(myUserId, MY_CARD.toString(), "1").andReturn());
            awaitLockWaiters(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            for (MvcResult response : List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))) {
                assertThat(response.getResponse().getStatus()).isEqualTo(200);
                versionNos.add(JsonPath.read(response.getResponse().getContentAsString(), "$.data.version.versionNo"));
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(versionNos).containsExactlyInAnyOrder(3, 4);
        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "USER_EDIT", false),
                new VersionRow(3, "RESTORE", false),
                new VersionRow(4, "RESTORE", false));
        assertThat(slotsOf(cardId, 3)).extracting(SlotRow::body).containsExactly("처음 상황");
        assertThat(slotsOf(cardId, 4)).extracting(SlotRow::body).containsExactly("처음 상황");

    }

    @Test
    @DisplayName("지금 버전 복원이 다른 복원 뒤에 줄 서면 앞 복원이 끝난 뒤 카드 행 잠금 안에서 판단해, 이제 예전 버전이 된 그 번호를 새 RESTORE 버전으로 되살린다")
    void currentVersionCheckWaitsForEarlierRestore() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "처음 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "S", "지금 상황", "USER_STATED", null);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            // 1번 복원이 먼저 카드 행에서 기다리고 2번(지금 버전) 복원이 그 뒤에 서게 한 뒤 풀어, 뒤 요청이 바뀐 지금 번호로 판단하는지 확인한다
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> older = pool.submit(() -> restore(myUserId, MY_CARD.toString(), "1").andReturn());
            awaitLockWaiters(1);
            Future<MvcResult> current = pool.submit(() -> restore(myUserId, MY_CARD.toString(), "2").andReturn());
            awaitLockWaiters(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            MvcResult olderResponse = older.get(30, TimeUnit.SECONDS);
            MvcResult currentResponse = current.get(30, TimeUnit.SECONDS);
            assertThat(olderResponse.getResponse().getStatus()).isEqualTo(200);
            assertThat(currentResponse.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) JsonPath.read(olderResponse.getResponse().getContentAsString(), "$.data.version.versionNo"))
                    .isEqualTo(3);
            assertThat((Integer) JsonPath.read(currentResponse.getResponse().getContentAsString(), "$.data.version.versionNo"))
                    .isEqualTo(4);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "USER_EDIT", false),
                new VersionRow(3, "RESTORE", false),
                new VersionRow(4, "RESTORE", false));
        assertThat(slotsOf(cardId, 3)).extracting(SlotRow::body).containsExactly("처음 상황");
        assertThat(slotsOf(cardId, 4)).extracting(SlotRow::body).containsExactly("지금 상황");

    }

    @Test
    @DisplayName("복원 응답은 FE Card 키를 모두 담고, 복원한 결과를 카드 상세 조회와 같은 값으로 싣는다")
    void restoreResponseMatchesCardDetail() throws Exception {

        Long cardId = confirmedCard();
        jdbc.update("UPDATE card SET period = ?, user_repository_id = ?, mask_rules = ?::jsonb WHERE id = ?",
                "2024년 상반기", myRepoId, "[{\"from\": \"우리 팀\", \"to\": \"팀\"}]", cardId);
        Long result = fixtures.insertStatement(cardId, 1, "R", "커밋으로 쓴 결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, secondCommit);
        fixtures.insertStatementEvidence(result, firstCommit);

        MvcResult written = restore(myUserId, MY_CARD.toString(), "1")
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> card = JsonPath.read(written.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
        assertThat(card.get("status")).isEqualTo("DRAFT");
        assertThat(((Map<?, ?>) card.get("version")).get("versionNo")).isEqualTo(3);
        assertSameAsDetail(card);

    }

    // 1번 버전(상황만) 다음 2번 확정본(상황·행동)이 현재 버전인 확정 카드로, 시각은 모두 OLD 다
    private Long confirmedCard() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "처음 상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.insertStatement(cardId, 2, "S", "확정한 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "확정한 행동", "USER_STATED", null);
        fixtures.confirmCard(cardId);
        age(cardId);

        return cardId;

    }

    private Long aiCard(UUID publicId, Long userId) {

        Long cardId = fixtures.insertCard(publicId, userId);
        jdbc.update("UPDATE card SET origin = 'AI' WHERE id = ?", cardId);
        jdbc.update("UPDATE card_version SET source = 'AI_DRAFT' WHERE card_id = ?", cardId);

        return cardId;

    }

    private void versionSavedAt(Long cardId, int versionNo, String at) {

        jdbc.update("UPDATE card_version SET updated_at = ?::timestamptz WHERE card_id = ? AND version_no = ?",
                at, cardId, versionNo);

    }

    private List<String> summariesOf(UUID publicId) throws Exception {

        String body = versions(myUserId, publicId.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return JsonPath.read(body, "$.data[*].summary");

    }

    private ResultActions versions(Long userId, String id) throws Exception {

        return mvc.perform(get("/api/cards/{id}/versions", id).with(loggedInAs(userId)));

    }

    private ResultActions versionOf(Long userId, String id, String versionNo) throws Exception {

        return mvc.perform(get("/api/cards/{id}/versions/{no}", id, versionNo).with(loggedInAs(userId)));

    }

    private ResultActions restore(Long userId, String id, String versionNo) throws Exception {

        return mvc.perform(post("/api/cards/{id}/versions/{no}/restore", id, versionNo)
                .with(loggedInAs(userId)).with(csrf()));

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

    private List<Map<String, Object>> rowsOfVersionsUpTo(Long cardId, int versionNo) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList(
                "SELECT version_no, source, is_confirmed, updated_at FROM card_version WHERE card_id = ? AND version_no <= ? ORDER BY version_no",
                cardId, versionNo));
        rows.addAll(jdbc.queryForList("""
                SELECT s.id, s.version_no, s.star_slot, s.body, s.evidence_type, s.confidence, s.source_turn_id,
                       ARRAY(SELECT e.commit_id FROM statement_evidence e WHERE e.statement_id = s.id ORDER BY e.id)::text AS commits
                FROM card_statement s WHERE s.card_id = ? AND s.version_no <= ? ORDER BY s.id
                """, cardId, versionNo));

        return rows;

    }

    private List<Map<String, Object>> stateOf(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(cardRowOf(cardId));
        rows.addAll(rowsOfVersionsUpTo(cardId, Short.MAX_VALUE));

        return rows;

    }

    private List<VersionRow> versionsOf(Long cardId) {

        return jdbc.query(
                "SELECT version_no, source, is_confirmed FROM card_version WHERE card_id = ? ORDER BY version_no",
                (row, rowNum) -> new VersionRow(row.getInt("version_no"), row.getString("source"),
                        row.getBoolean("is_confirmed")),
                cardId);

    }

    // 칸 글자 순서(A·R·S·T)로, 근거 커밋은 연결한 순서대로 읽는다
    private List<SlotRow> slotsOf(Long cardId, int versionNo) {

        return jdbc.query("""
                SELECT s.star_slot, s.body, s.evidence_type, s.confidence, s.source_turn_id,
                       ARRAY(SELECT e.commit_id FROM statement_evidence e WHERE e.statement_id = s.id ORDER BY e.id) AS commits
                FROM card_statement s WHERE s.card_id = ? AND s.version_no = ? ORDER BY s.star_slot
                """,
                (row, rowNum) -> new SlotRow(row.getString("star_slot"), row.getString("body"),
                        row.getString("evidence_type"), row.getString("confidence"),
                        row.getObject("source_turn_id", Long.class),
                        Arrays.asList((Long[]) row.getArray("commits").getArray())),
                cardId, versionNo);

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

    private record SlotRow(String slot, String body, String evidenceType, String confidence, Long turnId,
                           List<Long> commits) {
    }
}
