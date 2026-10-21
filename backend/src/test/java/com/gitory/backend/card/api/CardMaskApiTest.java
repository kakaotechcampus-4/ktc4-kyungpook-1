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
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
class CardMaskApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Map<String, String> TEAM_RULE = Map.of("from", "우리 팀", "to", "팀");
    private static final Map<String, String> COMPANY_RULE = Map.of("from", "A사", "to", "");

    // FE schemas.ts 의 Card·CardVersion 이 요구하는 키로, 값이 null 이어도 키는 빠지면 안 된다
    private static final String[] CARD_KEYS = {"id", "kind", "status", "title", "areas", "techs", "period", "repo",
            "candidate", "version", "evidence", "lowConfidenceFields", "droppedFields", "maskRules", "generation",
            "interviewTurns", "confirmedAt", "createdAt"};
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
    ObjectMapper mapper;

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
    @DisplayName("가리기 규칙은 받은 순서와 글 그대로(앞뒤 공백·빈 to·같은 규칙 반복 포함) 저장되고 응답 maskRules 도 같으며, 네 칸 글은 바뀌지 않는다")
    void storesRulesAsReceived() throws Exception {

        Long cardId = draftCard();
        List<Map<String, String>> rules = List.of(
                Map.of("from", " 우리 팀 ", "to", "  팀"),
                Map.of("from", "Z사", "to", ""),
                Map.of("from", "A사", "to", "B사 "),
                Map.of("from", "Z사", "to", ""));

        MvcResult saved = mask(myUserId, MY_CARD.toString(), rulesBody(rules))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.version.situation").value("  우리 팀과 A사 프로젝트  "))
                .andExpect(jsonPath("$.data.version.task").value(nullValue()))
                .andExpect(jsonPath("$.data.version.action").value("우리 팀이 한 일"))
                .andExpect(jsonPath("$.data.version.result").value(nullValue()))
                .andReturn();

        List<Map<String, Object>> answered = JsonPath.read(
                saved.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.maskRules");
        assertThat(answered).isEqualTo(rules);
        assertThat(storedRulesOf(cardId)).isEqualTo(rules);
        assertThat(slotsOf(cardId, 2)).extracting(SlotRow::body)
                .containsExactly("우리 팀이 한 일", "  우리 팀과 A사 프로젝트  ");

    }

    @Test
    @DisplayName("규칙이 바뀌면 지금 버전의 네 칸과 근거 커밋 연결을 복사한 MASK 버전이 현재 번호+1 로 생겨 현재 버전이 되고, 예전 버전은 그대로이며 카드 수정 시각이 바뀐다")
    void changedRulesCreateMaskVersion() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        jdbc.update("UPDATE card SET origin = 'AI' WHERE id = ?", cardId);
        jdbc.update("UPDATE card_version SET source = 'AI_DRAFT' WHERE card_id = ?", cardId);
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
        age(cardId);
        List<Map<String, Object>> olderBefore = rowsOfVersionsUpTo(cardId, 2);

        mask(myUserId, MY_CARD.toString(), rulesBody(List.of(TEAM_RULE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNo").value(3))
                .andExpect(jsonPath("$.data.version.source").value("MASK"))
                .andExpect(jsonPath("$.data.version.situation").value("AI 상황"))
                .andExpect(jsonPath("$.data.version.task").value("고른 답"))
                .andExpect(jsonPath("$.data.version.action").value("흐린 행동"))
                .andExpect(jsonPath("$.data.version.result").value(nullValue()))
                .andExpect(jsonPath("$.data.lowConfidenceFields[0].field").value("A"));

        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "AI_DRAFT", false),
                new VersionRow(2, "INTERVIEW", false),
                new VersionRow(3, "MASK", false));
        assertThat(slotsOf(cardId, 3)).containsExactly(
                new SlotRow("A", "흐린 행동", "COMMIT", "LOW", null, List.of(firstCommit)),
                new SlotRow("S", "AI 상황", "COMMIT", "HIGH", null, List.of(secondCommit, firstCommit)),
                new SlotRow("T", "고른 답", "USER_SELECTED", null, turn, List.of()));
        assertThat(rowsOfVersionsUpTo(cardId, 2)).isEqualTo(olderBefore);
        Map<String, Object> card = cardRowOf(cardId);
        assertThat(((Number) card.get("current_version")).intValue()).isEqualTo(3);
        assertThat(((Timestamp) card.get("updated_at")).toInstant()).isAfter(OLD);

    }

    @ParameterizedTest
    @MethodSource("sameRules")
    @DisplayName("저장된 규칙과 순서까지 같은 규칙을 보내면(둘 다 빈 목록 포함) 200 으로 지금 카드가 오고 새 버전도 카드 수정 시각 변경도 없다")
    void identicalRulesCreateNoVersion(List<Map<String, String>> rules) throws Exception {

        Long cardId = draftCard();
        storeRules(cardId, rules);
        List<Map<String, Object>> before = stateOf(cardId);

        MvcResult saved = mask(myUserId, MY_CARD.toString(), rulesBody(rules))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNo").value(1))
                .andExpect(jsonPath("$.data.version.source").value("USER_EDIT"))
                .andReturn();

        List<Map<String, Object>> answered = JsonPath.read(
                saved.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.maskRules");
        assertThat(answered).isEqualTo(rules);
        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("순서만 바뀐 규칙도 바뀐 것으로 보아 그 순서로 저장하고 MASK 버전을 만든다")
    void reorderedRulesAreAChange() throws Exception {

        Long cardId = draftCard();
        storeRules(cardId, List.of(TEAM_RULE, COMPANY_RULE));

        mask(myUserId, MY_CARD.toString(), rulesBody(List.of(COMPANY_RULE, TEAM_RULE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.version.source").value("MASK"))
                .andExpect(jsonPath("$.data.maskRules[0].from").value("A사"))
                .andExpect(jsonPath("$.data.maskRules[1].from").value("우리 팀"));

        assertThat(storedRulesOf(cardId)).isEqualTo(List.of(COMPANY_RULE, TEAM_RULE));
        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "MASK", false));

    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("규칙 목록이 없거나 null·배열이 아니거나 21개 이상이거나, 규칙이 null 이거나 from·to 가 없거나, from 이 공백뿐(NBSP 포함)이거나 from·to 가 100자를 넘으면 400 INVALID_REQUEST 이고 카드는 그대로다")
    void rejectsInvalidRules(String body) throws Exception {

        Long cardId = draftCard();
        storeRules(cardId, List.of(TEAM_RULE));
        List<Map<String, Object>> before = stateOf(cardId);

        mask(myUserId, MY_CARD.toString(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @MethodSource("rulesWithNul")
    @DisplayName("규칙의 from 이나 to 에 U+0000 이 있으면 400 INVALID_REQUEST 이고 저장된 규칙도 버전도 그대로다")
    void rejectsNulInRule(Map<String, String> rule) throws Exception {

        Long cardId = draftCard();
        storeRules(cardId, List.of(TEAM_RULE));
        List<Map<String, Object>> before = stateOf(cardId);

        mask(myUserId, MY_CARD.toString(), rulesBody(List.of(COMPANY_RULE, rule)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("규칙 20개, from·to 각 100자, 빈 to 는 받아 그대로 저장한다")
    void acceptsRulesAtLimits() throws Exception {

        Long cardId = draftCard();
        List<Map<String, String>> rules = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String from = String.valueOf((char) ('가' + i)).repeat(100);
            rules.add(Map.of("from", from, "to", i == 0 ? "" : "나".repeat(100)));
        }

        mask(myUserId, MY_CARD.toString(), rulesBody(rules))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNo").value(2));

        assertThat(storedRulesOf(cardId)).isEqualTo(rules);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesForConfirmedCard")
    @DisplayName("확정한 카드에 가리기를 보내면 규칙이 같든 다르든 409 CARD_CONFIRMED 이고, 규칙 목록이 없거나 잘못된 규칙(21개·to 없음·NBSP 뿐인 from·101자·U+0000)도 400 보다 먼저 409 이며 규칙도 버전도 바뀌지 않는다")
    void confirmedCardIsConflict(String body) throws Exception {

        Long cardId = draftCard();
        storeRules(cardId, List.of(TEAM_RULE));
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.insertStatement(cardId, 2, "S", "  우리 팀과 A사 프로젝트  ", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "우리 팀이 한 일", "USER_STATED", null);
        fixtures.confirmCard(cardId);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        mask(myUserId, MY_CARD.toString(), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("CARD_CONFIRMED"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("남의 카드에 가리기를 보내면 없는 카드와 똑같이 404 NOT_FOUND 이고 그 카드로 EDIT_CARD 거절 기록이 하나 남으며 그 카드는 그대로다")
    void othersCardIsNotFoundAndRecorded() throws Exception {

        // 남의 카드 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        fixtures.insertStatement(othersCardId, 1, "S", "남의 상황", "USER_STATED", null);
        List<Map<String, Object>> before = stateOf(othersCardId);

        mask(myUserId, OTHERS_CARD.toString(), rulesBody(List.of(TEAM_RULE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED"));
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 에 가리기를 보내면 404 NOT_FOUND 이고 거절 기록은 남지 않는다")
    void unknownCardIsNotFound(String id) throws Exception {

        mask(myUserId, id, rulesBody(List.of(TEAM_RULE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("같은 새 규칙이 동시에 두 번 오면 카드 행 잠금으로 차례로 처리돼 둘 다 200 이고 MASK 버전은 하나만 생긴다")
    void concurrentSameRulesCreateOneVersion() throws Exception {

        Long cardId = draftCard();
        String body = rulesBody(List.of(TEAM_RULE, COMPANY_RULE));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            // 두 요청이 모두 같은 카드 행에서 기다리게 만든 뒤 풀어, 뒤 요청이 앞 요청이 저장한 규칙을 보는지 확인한다
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> first = pool.submit(() -> mask(myUserId, MY_CARD.toString(), body).andReturn());
            Future<MvcResult> second = pool.submit(() -> mask(myUserId, MY_CARD.toString(), body).andReturn());
            awaitLockWaiters(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            for (MvcResult response : List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))) {
                assertThat(response.getResponse().getStatus()).isEqualTo(200);
                assertThat((Integer) JsonPath.read(response.getResponse().getContentAsString(), "$.data.version.versionNo"))
                        .isEqualTo(2);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(versionsOf(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "MASK", false));
        assertThat(storedRulesOf(cardId)).isEqualTo(List.of(TEAM_RULE, COMPANY_RULE));

    }

    @Test
    @DisplayName("가리기 응답은 FE Card 키를 모두 담고, 저장한 결과를 카드 상세 조회와 같은 값으로 싣는다")
    void responseMatchesCardDetail() throws Exception {

        Long cardId = draftCard();
        jdbc.update("UPDATE card SET period = ?, user_repository_id = ? WHERE id = ?", "2024년 상반기", myRepoId, cardId);
        Long result = fixtures.insertStatement(cardId, 1, "R", "커밋으로 쓴 결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, secondCommit);
        fixtures.insertStatementEvidence(result, firstCommit);

        MvcResult written = mask(myUserId, MY_CARD.toString(), rulesBody(List.of(TEAM_RULE, COMPANY_RULE)))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> card = JsonPath.read(written.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
        assertThat(((Map<?, ?>) card.get("version")).get("versionNo")).isEqualTo(2);
        assertThat(card.get("maskRules")).isEqualTo(List.of(TEAM_RULE, COMPANY_RULE));
        assertSameAsDetail(card);

    }

    static Stream<Arguments> sameRules() {

        return Stream.of(
                Arguments.of(Named.of("빈 목록", List.of())),
                Arguments.of(Named.of("규칙 둘", List.of(TEAM_RULE, COMPANY_RULE))));

    }

    static Stream<Arguments> invalidBodies() {

        String rule = "{\"from\": \"우리 팀\", \"to\": \"팀\"}";

        return Stream.of(
                Arguments.of(Named.of("rules 없음", "{}")),
                Arguments.of(Named.of("rules 가 null", "{\"rules\": null}")),
                Arguments.of(Named.of("rules 가 글", "{\"rules\": \"우리 팀\"}")),
                Arguments.of(Named.of("rules 가 객체", "{\"rules\": " + rule + "}")),
                Arguments.of(Named.of("규칙 21개", "{\"rules\": [" + String.join(", ", Collections.nCopies(21, rule)) + "]}")),
                Arguments.of(Named.of("규칙이 null", "{\"rules\": [null]}")),
                Arguments.of(Named.of("from 없음", "{\"rules\": [{\"to\": \"팀\"}]}")),
                Arguments.of(Named.of("to 없음", "{\"rules\": [{\"from\": \"우리 팀\"}]}")),
                Arguments.of(Named.of("from 이 빈 글", "{\"rules\": [{\"from\": \"\", \"to\": \"팀\"}]}")),
                Arguments.of(Named.of("from 이 공백뿐", "{\"rules\": [{\"from\": \" \\t\\n \", \"to\": \"팀\"}]}")),
                Arguments.of(Named.of("from 이 NBSP 뿐", "{\"rules\": [{\"from\": \"\\u00A0\", \"to\": \"팀\"}]}")),
                Arguments.of(Named.of("from 101자", "{\"rules\": [{\"from\": \"" + "가".repeat(101) + "\", \"to\": \"팀\"}]}")),
                Arguments.of(Named.of("to 101자", "{\"rules\": [{\"from\": \"우리 팀\", \"to\": \"" + "가".repeat(101) + "\"}]}")));

    }

    static Stream<Arguments> rulesWithNul() {

        return Stream.of(
                Arguments.of(Named.of("from 가운데 U+0000", Map.of("from", "홍길\u0000동", "to", "팀원"))),
                Arguments.of(Named.of("to 끝에 U+0000", Map.of("from", "홍길동", "to", "팀원\u0000"))));

    }

    static Stream<Arguments> bodiesForConfirmedCard() {

        String rule = "{\"from\": \"우리 팀\", \"to\": \"팀\"}";

        return Stream.of(
                Arguments.of(Named.of("지금과 같은 규칙", "{\"rules\": [" + rule + "]}")),
                Arguments.of(Named.of("바뀐 규칙", "{\"rules\": [{\"from\": \"A사\", \"to\": \"\"}]}")),
                Arguments.of(Named.of("rules 없음", "{}")),
                Arguments.of(Named.of("규칙 21개", "{\"rules\": [" + String.join(", ", Collections.nCopies(21, rule)) + "]}")),
                Arguments.of(Named.of("to 없음", "{\"rules\": [{\"from\": \"우리 팀\"}]}")),
                Arguments.of(Named.of("from 이 NBSP 뿐", "{\"rules\": [{\"from\": \"\\u00A0\", \"to\": \"팀\"}]}")),
                Arguments.of(Named.of("from 101자", "{\"rules\": [{\"from\": \"" + "가".repeat(101) + "\", \"to\": \"팀\"}]}")),
                Arguments.of(Named.of("from 가운데 U+0000", "{\"rules\": [{\"from\": \"홍길\\u0000동\", \"to\": \"팀원\"}]}")));

    }

    // 1번 직접 수정 버전에 상황(앞뒤 공백 포함)과 행동만 쓴 작성 중 카드로, 시각은 모두 OLD 다
    private Long draftCard() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "  우리 팀과 A사 프로젝트  ", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "A", "우리 팀이 한 일", "USER_STATED", null);
        age(cardId);

        return cardId;

    }

    private void storeRules(Long cardId, List<Map<String, String>> rules) {

        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?", mapper.writeValueAsString(rules), cardId);

    }

    private List<Object> storedRulesOf(Long cardId) {

        return JsonPath.read(jdbc.queryForObject("SELECT mask_rules::text FROM card WHERE id = ?", String.class, cardId),
                "$");

    }

    private String rulesBody(List<Map<String, String>> rules) {

        return mapper.writeValueAsString(Map.of("rules", rules));

    }

    private ResultActions mask(Long userId, String id, String body) throws Exception {

        return mvc.perform(post("/api/cards/{id}/mask", id)
                .with(loggedInAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

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
