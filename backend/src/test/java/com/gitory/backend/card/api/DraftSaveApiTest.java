package com.gitory.backend.card.api;

import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class DraftSaveApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final String LIMIT_TEXT = "가".repeat(1000);
    private static final String OVER_LIMIT_TEXT = "가".repeat(1001);

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
    private Long firstCommit;
    private Long secondCommit;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        firstCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        secondCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @Test
    @DisplayName("남의 카드에 임시 저장하면 없는 카드와 똑같이 404 이고, 요청자·EDIT_CARD·그 카드 내부 id 로 거절 기록을 남기며 그 카드는 그대로다")
    void othersCardIsNotFoundAndRecorded() throws Exception {

        // 남의 카드 id 가 내 사용자 id 와 같아지면 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 먼저 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        fixtures.insertStatement(othersCardId, 1, "S", "남의 상황", "USER_STATED", null);
        List<Map<String, Object>> before = stateOf(othersCardId);

        save(OTHERS_CARD, "덮어쓰려는 상황", null, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED"));
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("없는 카드나 UUID 가 아닌 id 로 임시 저장하면 404 이고 거절 기록을 남기지 않는다")
    void unknownOrMalformedCardIsNotFoundWithoutRecord() throws Exception {

        save(UUID.randomUUID(), "상황", null, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(patchDraft("not-a-uuid", "상황", null, null, null).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("작성 중 카드는 네 칸을 확인 필요 없이 모두 채워 저장하면 확정되고, 비운 칸이 있으면 작성 중으로 남는다")
    void savingAllFourSlotsConfirmsDraftCard() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);

        save(MY_CARD, "상황", null, "행동", null).andExpect(status().isOk());
        assertThat(statusOf(cardId)).isEqualTo("DRAFT");

        save(MY_CARD, "상황", "맡은 일", "행동", "결과").andExpect(status().isOk());
        assertThat(statusOf(cardId)).isEqualTo("CONFIRMED");
        assertThat(confirmedAtOf(cardId)).isNotNull();

    }

    @Test
    @DisplayName("확정된 카드도 다시 열지 않고 새 버전에 저장해 상황·행동이 남으면 확정으로 남고, 행동을 비우면 작성 중으로 돌아간다")
    void confirmedCardIsSavedAndReturnsToDraftOnlyWhenActionIsCleared() throws Exception {

        Long cleared = confirmedCard();
        UUID keptId = UUID.randomUUID();
        Long kept = fixtures.insertCard(keptId, myUserId);
        fixtures.insertCardVersion(kept, 2, "USER_EDIT", true);
        fixtures.insertStatement(kept, 2, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(kept, 2, "A", "행동", "USER_STATED", null);
        fixtures.confirmCard(kept);

        save(keptId, "고친 상황", null, "행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(3));
        assertThat(statusOf(kept)).isEqualTo("CONFIRMED");

        save(MY_CARD, "고친 상황", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(3));
        assertThat(statusOf(cleared)).isEqualTo("DRAFT");
        assertThat(confirmedAtOf(cleared)).isNull();

    }

    @ParameterizedTest(name = "{0} 칸이 1,001자")
    @EnumSource(StarField.class)
    @DisplayName("1,000자를 넘는 칸이 하나라도 있으면 400 INVALID_REQUEST 이고 버전·문장·시각이 그대로다")
    void rejectsSlotOverLimit(StarField tooLong) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        Map<StarField, String> texts = new EnumMap<>(StarField.class);
        for (StarField field : StarField.values()) {
            texts.put(field, field == tooLong ? OVER_LIMIT_TEXT : "새 글");
        }

        save(MY_CARD, texts.get(StarField.S), texts.get(StarField.T), texts.get(StarField.A), texts.get(StarField.R))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("정확히 1,000자인 칸은 네 칸 모두 그대로 저장된다")
    void savesSlotsAtLimit() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);

        save(MY_CARD, LIMIT_TEXT, LIMIT_TEXT, LIMIT_TEXT, LIMIT_TEXT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.situation").value(LIMIT_TEXT))
                .andExpect(jsonPath("$.data.result").value(LIMIT_TEXT));

        assertThat(slotsOf(cardId, 1).values()).extracting(StatementRow::body)
                .containsExactly(LIMIT_TEXT, LIMIT_TEXT, LIMIT_TEXT, LIMIT_TEXT);

    }

    @ParameterizedTest(name = "{0} 키 없음")
    @ValueSource(strings = {"situation", "task", "action", "result"})
    @DisplayName("본문에 situation·task·action·result 네 키 중 하나라도 없으면 나머지 칸이 지금 글과 같아도 400 INVALID_REQUEST 이고 버전·문장·시각이 그대로다")
    void rejectsMissingKey(String missing) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "A", "행동", "USER_STATED", null);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        Map<String, String> fields = fieldsOf("상황", null, "행동", null);
        fields.remove(missing);

        save(MY_CARD, fields)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("키가 빠진 본문이어도 남의 카드면 404 와 거절 기록으로 먼저 답하고, 내 카드는 확정했든 작성 중이든 400 이다")
    void checksOwnershipBeforeMissingKey() throws Exception {

        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        confirmedCard();
        UUID draftCard = UUID.randomUUID();
        fixtures.insertCard(draftCard, myUserId);
        Map<String, String> withoutResult = fieldsOf("상황", null, "행동", null);
        withoutResult.remove("result");

        save(OTHERS_CARD, withoutResult)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        save(MY_CARD, withoutResult)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        save(draftCard, withoutResult)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED"));

    }

    @ParameterizedTest(name = "{0} 칸에 U+0000")
    @EnumSource(StarField.class)
    @DisplayName("칸 글에 U+0000 이 있으면 400 INVALID_REQUEST 이고, 앞 칸까지 쓰던 새 버전도 남지 않아 버전·문장·시각이 그대로다")
    void rejectsNulInSlot(StarField withNul) throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, firstCommit);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        Map<StarField, String> texts = new EnumMap<>(StarField.class);
        for (StarField field : StarField.values()) {
            texts.put(field, field == withNul ? "인덱스를\u0000추가했다" : "새 글");
        }

        save(MY_CARD, texts.get(StarField.S), texts.get(StarField.T), texts.get(StarField.A), texts.get(StarField.R))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("규칙 여러 개에 함께 걸리면 남의 카드 404, 글자 수 400 순서로 답하고 확정한 카드도 글자 수로 400 이다")
    void checksOwnershipThenLength() throws Exception {

        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        fixtures.insertCardVersion(othersCardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(othersCardId);
        confirmedCard();
        UUID draftCard = UUID.randomUUID();
        fixtures.insertCard(draftCard, myUserId);

        save(OTHERS_CARD, OVER_LIMIT_TEXT, null, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        save(MY_CARD, OVER_LIMIT_TEXT, null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        save(draftCard, OVER_LIMIT_TEXT, null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

    }

    @Test
    @DisplayName("보낸 네 칸이 덮어쓸 수 없는 현재 버전과 같으면 빈 칸을 null·빈 글·공백 어느 것으로 보내도 아무것도 쓰지 않고 현재 번호와 그 버전 시각을 돌려준다")
    void identicalSaveOnLockedVersionWritesNothing() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, firstCommit);
        fixtures.insertStatement(cardId, 1, "A", "AI 행동", "COMMIT", "LOW");
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        save(MY_CARD, "AI 상황", "", "AI 행동", " \t\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.situation").value("AI 상황"))
                .andExpect(jsonPath("$.data.task").value(nullValue()))
                .andExpect(jsonPath("$.data.result").value(nullValue()))
                .andExpect(jsonPath("$.data.savedAt").value(OLD.toString()));
        save(MY_CARD, "AI 상황", null, "AI 행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.savedAt").value(OLD.toString()));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("보낸 네 칸이 덮어쓸 수 있는 직접 수정 현재 버전과 같아도 문장을 다시 쓰지 않고 그 버전 시각을 그대로 돌려준다")
    void identicalSaveOnEditableVersionWritesNothing() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        save(MY_CARD, "상황", null, "", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.savedAt").value(OLD.toString()));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("현재 버전이 확정본이 아닌 직접 수정 버전이면 그 버전을 덮어써 번호는 그대로이고 그 버전과 카드의 시각이 갱신된다")
    void overwritesEditableVersion() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "옛 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "T", "옛 과제", "USER_STATED", null);
        age(cardId);

        MvcResult saved = save(MY_CARD, "새 상황", null, "행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.situation").value("새 상황"))
                .andExpect(jsonPath("$.data.task").value(nullValue()))
                .andReturn();

        assertThat(versions(cardId)).containsExactly(new VersionRow(1, "USER_EDIT", false));
        assertThat(slotsOf(cardId, 1)).containsOnlyKeys("S", "A");
        assertThat(slotsOf(cardId, 1).get("S")).isEqualTo(userStated("새 상황"));
        assertThat(slotsOf(cardId, 1).get("A")).isEqualTo(userStated("행동"));
        assertThat(versionUpdatedAt(cardId, 1)).isAfter(OLD);
        assertThat(cardUpdatedAt(cardId)).isAfter(OLD);
        assertThat(Instant.parse(JsonPath.read(saved.getResponse().getContentAsString(), "$.data.savedAt"))).isAfter(OLD);

    }

    @ParameterizedTest(name = "{0} 확정본={1}")
    @CsvSource({"AI_DRAFT, false", "INTERVIEW, false", "MASK, false", "RESTORE, false", "USER_EDIT, true"})
    @DisplayName("현재 버전이 AI 초안·답변 반영·가리기·복원이거나 다시 연 카드의 확정본이면 첫 저장 때 직접 수정 새 버전을 만들어 현재 버전으로 삼고 원래 버전 문장은 그대로 둔다")
    void firstSaveOnLockedVersionCreatesNext(String source, boolean confirmed) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, source, confirmed);
        fixtures.insertStatement(cardId, 2, "S", "상황", "USER_STATED", null);
        Long action = fixtures.insertStatement(cardId, 2, "A", "행동", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(action, firstCommit);
        age(cardId);
        Map<String, StatementRow> original = slotsOf(cardId, 2);
        List<Long> originalIds = statementIds(cardId, 2);

        save(MY_CARD, "고친 상황", null, "행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(3));
        save(MY_CARD, "또 고친 상황", null, "행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(3));

        assertThat(versions(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, source, confirmed),
                new VersionRow(3, "USER_EDIT", false));
        assertThat(currentVersion(cardId)).isEqualTo(3);
        assertThat(cardUpdatedAt(cardId)).isAfter(OLD);
        assertThat(slotsOf(cardId, 2)).isEqualTo(original);
        assertThat(statementIds(cardId, 2)).isEqualTo(originalIds);
        assertThat(slotsOf(cardId, 3).get("S")).isEqualTo(userStated("또 고친 상황"));

    }

    @Test
    @DisplayName("새 버전을 만들 때 직전 버전과 공백까지 같은 칸은 출처·확신도·답변 턴·커밋 연결을 복사하고, 다른 칸은 직접 쓴 문장으로 바꿔 커밋 연결·확신도·턴을 비운다")
    void newVersionKeepsEvidenceOfUnchangedSlots() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        Long turnId = fixtures.insertInterviewTurn(cardId, 1, "A");
        Long situation = fixtures.insertStatement(cardId, 1, "S", "상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, secondCommit);
        fixtures.insertStatementEvidence(situation, firstCommit);
        Long task = fixtures.insertStatement(cardId, 1, "T", "과제", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(task, firstCommit);
        fixtures.insertStatement(cardId, 1, "A", "행동", "USER_SELECTED", "MEDIUM", turnId);
        Long result = fixtures.insertStatement(cardId, 1, "R", "결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, secondCommit);
        Map<String, StatementRow> original = slotsOf(cardId, 1);

        save(MY_CARD, "상황", "고친 과제", "행동", "결과 ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.result").value("결과 "));

        Map<String, StatementRow> edited = slotsOf(cardId, 2);
        assertThat(edited.get("S")).isEqualTo(new StatementRow("상황", "COMMIT", "HIGH", null,
                List.of(secondCommit, firstCommit)));
        assertThat(edited.get("T")).isEqualTo(userStated("고친 과제"));
        assertThat(edited.get("A")).isEqualTo(new StatementRow("행동", "USER_SELECTED", "MEDIUM", turnId, List.of()));
        assertThat(edited.get("R")).isEqualTo(userStated("결과 "));
        assertThat(slotsOf(cardId, 1)).isEqualTo(original);

    }

    @Test
    @DisplayName("직접 작성 카드의 1번 버전은 비교할 직전 버전이 없어 덮어쓴 칸이 모두 직접 쓴 문장이 된다")
    void firstManualVersionHasNothingToCompare() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, firstCommit);

        save(MY_CARD, "상황", "과제", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1));

        assertThat(slotsOf(cardId, 1)).containsOnlyKeys("S", "T");
        assertThat(slotsOf(cardId, 1).get("S")).isEqualTo(userStated("상황"));
        assertThat(slotsOf(cardId, 1).get("T")).isEqualTo(userStated("과제"));

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankTexts")
    @DisplayName("null·빈 글·공백 문자만 온 칸은 문장 행 없이 저장되고 응답과 카드 목록에서 빈 칸이다")
    void blankSlotHasNoRow(String blank) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "옛 상황", "USER_STATED", null);

        save(MY_CARD, blank, null, "행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.situation").value(nullValue()))
                .andExpect(jsonPath("$.data.action").value("행동"));

        assertThat(slotsOf(cardId, 1)).containsOnlyKeys("A");
        mvc.perform(get("/api/cards").with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].star.S").value("EMPTY"))
                .andExpect(jsonPath("$.data[0].star.A").value("FILLED"))
                .andExpect(jsonPath("$.data[0].userStatedCount").value(1));

    }

    @Test
    @DisplayName("글이 있는 칸은 앞뒤 공백과 줄바꿈까지 그대로 저장하고 그대로 돌려준다")
    void keepsSurroundingWhitespace() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        String situation = "  3인 팀이었다 \n";
        String task = " 글 ";
        String action = "\u00A0직접 설득했다\u00A0";

        save(MY_CARD, situation, task, action, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.situation").value(situation))
                .andExpect(jsonPath("$.data.task").value(task))
                .andExpect(jsonPath("$.data.action").value(action));

        assertThat(slotsOf(cardId, 1)).containsOnlyKeys("S", "T", "A");
        assertThat(slotsOf(cardId, 1).get("S").body()).isEqualTo(situation);
        assertThat(slotsOf(cardId, 1).get("T").body()).isEqualTo(task);
        assertThat(slotsOf(cardId, 1).get("A").body()).isEqualTo(action);

    }

    @Test
    @DisplayName("고친 직접 수정 버전에 직전 버전 원문을 다시 보내면 그 버전에 직전 버전의 근거와 확신도가 돌아오고 버전은 지워지지 않는다")
    void sendingOriginalBackRestoresEvidence() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(situation, firstCommit);
        Long action = fixtures.insertStatement(cardId, 1, "A", "AI 행동", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(action, secondCommit);

        save(MY_CARD, "고친 상황", null, "AI 행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2));
        assertThat(slotsOf(cardId, 2).get("S")).isEqualTo(userStated("고친 상황"));

        save(MY_CARD, "AI 상황", null, "AI 행동", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2));

        assertThat(versions(cardId)).containsExactly(
                new VersionRow(1, "AI_DRAFT", false),
                new VersionRow(2, "USER_EDIT", false));
        assertThat(currentVersion(cardId)).isEqualTo(2);
        assertThat(slotsOf(cardId, 2).get("S")).isEqualTo(new StatementRow("AI 상황", "COMMIT", "HIGH", null,
                List.of(firstCommit)));
        assertThat(slotsOf(cardId, 2).get("A")).isEqualTo(new StatementRow("AI 행동", "COMMIT", "LOW", null,
                List.of(secondCommit)));
        mvc.perform(get("/api/cards").with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data[0].star.S").value("FILLED"))
                .andExpect(jsonPath("$.data[0].star.A").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data[0].evidenceCount").value(2))
                .andExpect(jsonPath("$.data[0].userStatedCount").value(0));

    }

    @Test
    @DisplayName("같은 카드에 임시 저장 두 건이 동시에 오면 카드 행 잠금으로 차례로 처리돼 둘 다 200 이고 새 버전은 하나만 생긴다")
    void concurrentSavesCreateOneVersion() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "HIGH");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            // 두 요청이 모두 같은 카드 행에서 기다리게 만든 뒤 풀어, 앞 요청이 바꾼 버전을 뒤 요청이 보는지 확인한다
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> first = pool.submit(() -> save(MY_CARD, "첫 요청", null, null, null).andReturn());
            Future<MvcResult> second = pool.submit(() -> save(MY_CARD, "둘째 요청", null, null, null).andReturn());
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
                new VersionRow(1, "AI_DRAFT", false),
                new VersionRow(2, "USER_EDIT", false));
        assertThat(currentVersion(cardId)).isEqualTo(2);
        assertThat(slotsOf(cardId, 2)).containsOnlyKeys("S");

    }

    @Test
    @DisplayName("임시 저장 응답은 빈 칸도 null 로 실어 계약의 일곱 키를 모두 담는다")
    void responseHasEveryContractKey() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        MvcResult saved = save(MY_CARD, "상황", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andReturn();

        Map<String, Object> data = JsonPath.read(saved.getResponse().getContentAsString(), "$.data");
        assertThat(data).containsOnlyKeys("cardId", "versionNo", "situation", "task", "action", "result", "savedAt");
        assertThat(data.get("cardId")).isEqualTo(MY_CARD.toString());
        assertThat(data.get("versionNo")).isEqualTo(1);
        assertThat(data.get("situation")).isEqualTo("상황");
        assertThat(data.get("task")).isNull();
        assertThat(data.get("action")).isNull();
        assertThat(data.get("result")).isNull();
        assertThat(Instant.parse((String) data.get("savedAt"))).isAfter(OLD);

    }

    @Test
    @DisplayName("로그인하지 않은 임시 저장은 401, CSRF 토큰이 없는 임시 저장은 403 이고 카드는 그대로다")
    void saveNeedsSessionAndCsrf() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        List<Map<String, Object>> before = stateOf(cardId);

        mvc.perform(patchDraft(MY_CARD.toString(), "고친 상황", null, null, null).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(patchDraft(MY_CARD.toString(), "고친 상황", null, null, null).with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    static Stream<Arguments> blankTexts() {

        return Stream.of(
                Arguments.of(Named.of("null", null)),
                Arguments.of(Named.of("빈 글", "")),
                Arguments.of(Named.of("스페이스", "   ")),
                Arguments.of(Named.of("탭·줄바꿈", "\t\n")),
                Arguments.of(Named.of("줄바꿈 없는 공백 U+00A0", "\u00A0")),
                Arguments.of(Named.of("숫자·좁은 줄바꿈 없는 공백 U+2007·U+202F", "\u2007\u202F")),
                Arguments.of(Named.of("전각 공백 U+3000", "\u3000")),
                Arguments.of(Named.of("BOM U+FEFF", "\uFEFF")),
                Arguments.of(Named.of("스페이스와 BOM", "   \uFEFF")),
                Arguments.of(Named.of("스페이스와 자바 공백 U+001C", " \u001C")));

    }

    @Test
    @DisplayName("AI 초안 위에서 저장하면 새 버전에 안 고친 칸(그대로 비워 둔 칸 포함)의 메모는 따라오고 고친 칸의 메모는 없어진다")
    void carriesNotesOfUntouchedSlots() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(situation, firstCommit);
        fixtures.insertSlotNote(cardId, 1, "S", null, "근거에 없는 수치");
        fixtures.insertSlotNote(cardId, 1, "A", "TIMEOUT", null);
        fixtures.insertSlotNote(cardId, 1, "R", "NO_EVIDENCE", null);

        save(MY_CARD, "AI 상황", "직접 쓴 과제", "직접 쓴 행동", null).andExpect(status().isOk());

        assertThat(notesOf(cardId, 2)).containsExactly(
                new NoteRow("R", "NO_EVIDENCE", null),
                new NoteRow("S", null, "근거에 없는 수치"));

    }

    @Test
    @DisplayName("덮어쓰는 직접 수정 버전은 칸 메모를 다시 정해, 앞 저장에서 따라온 빈 칸 메모도 그 칸을 채우면 없어진다")
    void overwriteRecomputesNotes() throws Exception {

        Long cardId = aiCard(MY_CARD, myUserId);
        fixtures.insertSlotNote(cardId, 1, "A", "OVERCLAIM", null);
        fixtures.insertSlotNote(cardId, 1, "R", "NO_EVIDENCE", null);
        save(MY_CARD, "직접 쓴 상황", null, null, null).andExpect(status().isOk());

        save(MY_CARD, "직접 쓴 상황", null, null, "직접 쓴 결과").andExpect(status().isOk());

        assertThat(notesOf(cardId, 2)).containsExactly(new NoteRow("A", "OVERCLAIM", null));
        assertThat(jdbc.queryForObject("SELECT max(version_no) FROM card_version WHERE card_id = ?", Integer.class,
                cardId)).isEqualTo(2);

    }

    private Long confirmedCard() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "A", "행동", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.insertStatement(cardId, 2, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "행동", "USER_STATED", null);
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

    private void age(Long cardId) {

        OffsetDateTime old = OffsetDateTime.ofInstant(OLD, ZoneOffset.UTC);
        jdbc.update("UPDATE card SET updated_at = ? WHERE id = ?", old, cardId);
        jdbc.update("UPDATE card_version SET updated_at = ? WHERE card_id = ?", old, cardId);

    }

    private ResultActions save(UUID publicId, String situation, String task, String action, String result)
            throws Exception {

        return save(publicId, fieldsOf(situation, task, action, result));

    }

    private ResultActions save(UUID publicId, Map<String, String> fields) throws Exception {

        return mvc.perform(patchDraft(publicId.toString(), fields).with(loggedInAs(myUserId)).with(csrf()));

    }

    private MockHttpServletRequestBuilder patchDraft(String id, String situation, String task, String action,
                                                     String result) {

        return patchDraft(id, fieldsOf(situation, task, action, result));

    }

    private MockHttpServletRequestBuilder patchDraft(String id, Map<String, String> fields) {

        return patch("/api/cards/{id}/draft", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields));

    }

    private static Map<String, String> fieldsOf(String situation, String task, String action, String result) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("situation", situation);
        fields.put("task", task);
        fields.put("action", action);
        fields.put("result", result);

        return fields;

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

    private String statusOf(Long cardId) {

        return jdbc.queryForObject("SELECT status FROM card WHERE id = ?", String.class, cardId);

    }

    private Object confirmedAtOf(Long cardId) {

        return jdbc.queryForObject("SELECT confirmed_at FROM card WHERE id = ?", Object.class, cardId);

    }

    private List<Map<String, Object>> stateOf(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT current_version, status, updated_at FROM card WHERE id = ?", cardId));
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

    private int currentVersion(Long cardId) {

        return jdbc.queryForObject("SELECT current_version FROM card WHERE id = ?", Integer.class, cardId);

    }

    private Instant cardUpdatedAt(Long cardId) {

        return jdbc.queryForObject("SELECT updated_at FROM card WHERE id = ?", OffsetDateTime.class, cardId).toInstant();

    }

    private Instant versionUpdatedAt(Long cardId, int versionNo) {

        return jdbc.queryForObject("SELECT updated_at FROM card_version WHERE card_id = ? AND version_no = ?",
                OffsetDateTime.class, cardId, versionNo).toInstant();

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private static StatementRow userStated(String body) {

        return new StatementRow(body, "USER_STATED", null, null, List.of());

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

    private List<NoteRow> notesOf(Long cardId, int versionNo) {

        return jdbc.query("SELECT star_slot, drop_reason, review_reason FROM card_slot_note WHERE card_id = ? AND version_no = ? ORDER BY star_slot",
                (row, rowNum) -> new NoteRow(row.getString("star_slot"), row.getString("drop_reason"),
                        row.getString("review_reason")), cardId, versionNo);

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }

    private record NoteRow(String slot, String dropReason, String reviewReason) {
    }
}
