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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class CardMetadataApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TITLE_AT_LIMIT = "가".repeat(256);
    private static final String PERIOD_AT_LIMIT = "가".repeat(50);
    private static final String PERIOD_OVER_LIMIT = "가".repeat(51);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    private TestFixtures fixtures;

    private Long myUserId;
    private Long othersUserId;
    private Long commitId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        commitId = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("partialEdits")
    @DisplayName("제목만 보내면 제목만, 기간만 보내면 기간만, 둘 다 보내면 둘 다 바뀌고 보내지 않은 칸은 그대로다")
    void editsOnlySentFields(Map<String, String> sent, String title, String period) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "옛 제목", "2024.04");

        edit(MY_CARD, mapper.writeValueAsString(sent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.period").value(period));

        assertThat(titleAndPeriodOf(cardId)).containsExactly(title, period);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editsWithNullField")
    @DisplayName("값을 null 로 보낸 칸은 키가 없을 때처럼 그대로 두고, 함께 보낸 다른 칸만 바뀐다")
    void nullFieldIsLeftAsIs(String body, String title, String period) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "옛 제목", "2024.04");

        edit(MY_CARD, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.period").value(period));

        assertThat(titleAndPeriodOf(cardId)).containsExactly(title, period);

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("기간을 빈 글이나 공백뿐인 글로 보내면 기간을 지워 DB 는 NULL 이고 응답의 period 는 키가 있는 null 이다")
    void blankPeriodClearsPeriod(String blank) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");

        MvcResult edited = edit(MY_CARD, json("period", blank))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> data = JsonPath.read(edited.getResponse().getContentAsString(), "$.data");
        assertThat(data).containsKey("period");
        assertThat(data.get("period")).isNull();
        assertThat(titleAndPeriodOf(cardId)).containsExactly("제목", null);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesWithNothingToChange")
    @DisplayName("제목과 기간을 둘 다 보내지 않거나 둘 다 null 로 보내면 400 INVALID_REQUEST 이고 카드는 그대로다")
    void rejectsNothingToChange(String body) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        edit(MY_CARD, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTitles")
    @DisplayName("제목이 빈 글·공백뿐이거나 256자를 넘으면 400 INVALID_REQUEST 이고 카드는 그대로다")
    void rejectsBlankOrTooLongTitle(String title) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        edit(MY_CARD, json("title", title))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("정확히 256자인 제목과 앞뒤에 공백이 있는 제목은 받은 글 그대로 저장한다")
    void keepsTitleAsSent() throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", null);
        String spaced = "  로그인 세션 개선 \n";

        edit(MY_CARD, json("title", TITLE_AT_LIMIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(TITLE_AT_LIMIT));
        assertThat(titleAndPeriodOf(cardId)).containsExactly(TITLE_AT_LIMIT, null);

        edit(MY_CARD, json("title", spaced))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(spaced));
        assertThat(titleAndPeriodOf(cardId)).containsExactly(spaced, null);

    }

    @Test
    @DisplayName("기간이 50자를 넘으면 400 INVALID_REQUEST 이고 카드는 그대로다")
    void rejectsPeriodOverLimit() throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        edit(MY_CARD, json("period", PERIOD_OVER_LIMIT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("정확히 50자인 기간과 앞뒤에 공백이 있는 기간은 받은 글 그대로 저장한다")
    void keepsPeriodAsSent() throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", null);
        String spaced = " 2024.04 ~ 2024.06 ";

        edit(MY_CARD, json("period", PERIOD_AT_LIMIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value(PERIOD_AT_LIMIT));
        assertThat(titleAndPeriodOf(cardId)).containsExactly("제목", PERIOD_AT_LIMIT);

        edit(MY_CARD, json("period", spaced))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value(spaced));
        assertThat(titleAndPeriodOf(cardId)).containsExactly("제목", spaced);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editsRefreshingUpdatedAt")
    @DisplayName("제목이나 기간을 고치면 카드의 updatedAt 이 갱신되고, 응답은 cardId·title·period·updatedAt 키 4개이며 updatedAt 은 끝이 Z 인 ISO 시각으로 DB 값과 같다")
    void responseHasFourKeysAndUpdatedAt(Map<String, String> sent, String title, String period) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        age(cardId);

        MvcResult edited = edit(MY_CARD, mapper.writeValueAsString(sent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andReturn();

        Map<String, Object> data = JsonPath.read(edited.getResponse().getContentAsString(), "$.data");
        assertThat(data).containsOnlyKeys("cardId", "title", "period", "updatedAt");
        assertThat(data.get("cardId")).isEqualTo(MY_CARD.toString());
        assertThat(data.get("title")).isEqualTo(title);
        assertThat(data.get("period")).isEqualTo(period);
        String updatedAt = (String) data.get("updatedAt");
        assertThat(updatedAt).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        Instant saved = cardUpdatedAt(cardId);
        assertThat(saved).isAfter(OLD);
        assertThat(Instant.parse(updatedAt)).isEqualTo(saved);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesWithCurrentValues")
    @DisplayName("보낸 값이 모두 지금 값과 같으면 아무것도 바꾸지 않아 updatedAt 도 그대로이고, 200 으로 지금 제목·기간과 그 updatedAt 을 돌려준다")
    void sameValuesChangeNothing(String body) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        MvcResult edited = edit(MY_CARD, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("제목"))
                .andExpect(jsonPath("$.data.period").value("2024.04"))
                .andReturn();

        assertThat(updatedAtIn(edited)).isEqualTo(OLD);
        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "   ", "\t\n", "\u00A0"})
    @DisplayName("기간이 없는 카드에 빈 글이나 공백뿐인 기간(NBSP 포함)을 보내면 기간 없음과 같은 값이라 아무것도 바꾸지 않고 updatedAt 도 그대로다")
    void blankPeriodOnCardWithoutPeriodChangesNothing(String blank) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", null);
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        MvcResult edited = edit(MY_CARD, json("period", blank))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("제목"))
                .andReturn();

        Map<String, Object> data = JsonPath.read(edited.getResponse().getContentAsString(), "$.data");
        assertThat(data).containsKey("period");
        assertThat(data.get("period")).isNull();
        assertThat(updatedAtIn(edited)).isEqualTo(OLD);
        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editsWithOneChangedField")
    @DisplayName("보낸 값 가운데 하나라도 지금 값과 다르면(앞뒤 공백만 달라도) 다른 칸을 받은 글 그대로 바꾸고 updatedAt 을 갱신한다")
    void oneChangedFieldRefreshesUpdatedAt(String body, String title, String period) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        age(cardId);

        MvcResult edited = edit(MY_CARD, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.period").value(period))
                .andReturn();

        assertThat(titleAndPeriodOf(cardId)).containsExactly(title, period);
        assertThat(cardUpdatedAt(cardId)).isAfter(OLD).isEqualTo(updatedAtIn(edited));

    }

    @Test
    @DisplayName("제목·기간을 고쳐도 네 칸 문장·버전 행·현재 버전·근거 연결·가리기 규칙은 그대로이고 새 버전이 생기지 않는다")
    void keepsSlotsVersionsAndMaskRules() throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        jdbc.update("UPDATE card SET origin = 'AI', mask_rules = ?::jsonb WHERE id = ?",
                "[{\"from\":\"김철수\",\"to\":\"팀원 A\"}]", cardId);
        jdbc.update("UPDATE card_version SET source = 'AI_DRAFT' WHERE card_id = ?", cardId);
        Long original = fixtures.insertStatement(cardId, 1, "S", "AI 상황", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(original, commitId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        Long copied = fixtures.insertStatement(cardId, 2, "S", "AI 상황", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(copied, commitId);
        fixtures.insertStatement(cardId, 2, "A", "행동", "USER_STATED", null);
        List<Map<String, Object>> before = contentOf(cardId);

        edit(MY_CARD, json("title", "새 제목", "period", "2025.01"))
                .andExpect(status().isOk());

        assertThat(contentOf(cardId)).isEqualTo(before);
        assertThat(titleAndPeriodOf(cardId)).containsExactly("새 제목", "2025.01");

    }

    @Test
    @DisplayName("확정된 카드도 다시 열지 않고 제목·기간을 고치며, 카드는 확정으로 남는다")
    void confirmedCardAcceptsTitleAndPeriodAndStaysConfirmed() throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "A", "행동", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.insertStatement(cardId, 2, "S", "상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "행동", "USER_STATED", null);
        fixtures.confirmCard(cardId);
        age(cardId);

        edit(MY_CARD, "{\"title\":\"새 제목\",\"period\":\"2025.01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("새 제목"));

        assertThat(titleAndPeriodOf(cardId)).containsExactly("새 제목", "2025.01");
        assertThat(jdbc.queryForObject("SELECT status FROM card WHERE id = ?", String.class, cardId))
                .isEqualTo("CONFIRMED");

    }

    @Test
    @DisplayName("남의 카드를 고치면 본문과 상관없이 없는 카드와 똑같이 404 이고, 요청자·EDIT_CARD·그 카드 내부 id 로 거절 기록을 남기며 그 카드는 그대로다")
    void othersCardIsNotFoundAndRecorded() throws Exception {

        // 남의 카드 id 가 내 사용자 id 와 같아지면 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 먼저 만든다
        draftCard(UUID.randomUUID(), myUserId, "내 카드", null);
        Long othersCardId = draftCard(OTHERS_CARD, othersUserId, "남의 카드", "2024.04");
        List<Map<String, Object>> before = stateOf(othersCardId);

        edit(OTHERS_CARD, json("title", "가로챈 제목"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        edit(OTHERS_CARD, "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        AuditRow denied = new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED");
        assertThat(events()).containsExactly(denied, denied);
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("없는 카드나 UUID 가 아닌 id 를 고치면 404 이고 거절 기록을 남기지 않는다")
    void unknownOrMalformedCardIsNotFoundWithoutRecord() throws Exception {

        edit(UUID.randomUUID(), json("title", "새 제목"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(editRequest("not-a-uuid", json("title", "새 제목")).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("바른 제목과 51자 기간을 함께 보내 400 이 나면 제목도 updatedAt 도 바뀌지 않는다")
    void rejectedRequestChangesNothing() throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "옛 제목", "2024.04");
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        edit(MY_CARD, json("title", "새 제목", "period", PERIOD_OVER_LIMIT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(titleAndPeriodOf(cardId)).containsExactly("옛 제목", "2024.04");
        assertThat(cardUpdatedAt(cardId)).isEqualTo(OLD);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editsWithNul")
    @DisplayName("제목이나 기간에 U+0000 이 있으면 400 INVALID_REQUEST 이고, 함께 보낸 바른 제목도 저장하지 않아 카드는 그대로다")
    void rejectsNulInTitleOrPeriod(Map<String, String> sent) throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        age(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        edit(MY_CARD, mapper.writeValueAsString(sent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("로그인하지 않은 수정은 401, CSRF 토큰이 없는 수정은 403 이고 카드는 그대로다")
    void editNeedsSessionAndCsrf() throws Exception {

        Long cardId = draftCard(MY_CARD, myUserId, "제목", "2024.04");
        List<Map<String, Object>> before = stateOf(cardId);

        mvc.perform(editRequest(MY_CARD.toString(), json("title", "새 제목")).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(editRequest(MY_CARD.toString(), json("title", "새 제목")).with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    static Stream<Arguments> partialEdits() {

        return Stream.of(
                arguments(Named.of("제목만", Map.of("title", "새 제목")), "새 제목", "2024.04"),
                arguments(Named.of("기간만", Map.of("period", "2025.01")), "옛 제목", "2025.01"),
                arguments(Named.of("둘 다", Map.of("title", "새 제목", "period", "2025.01")), "새 제목", "2025.01"));

    }

    static Stream<Arguments> editsWithNullField() {

        return Stream.of(
                arguments(Named.of("제목 + 기간 null", "{\"title\":\"새 제목\",\"period\":null}"), "새 제목", "2024.04"),
                arguments(Named.of("제목 null + 기간", "{\"title\":null,\"period\":\"2025.01\"}"), "옛 제목", "2025.01"));

    }

    static Stream<Arguments> editsRefreshingUpdatedAt() {

        return Stream.of(
                arguments(Named.of("제목만", Map.of("title", "새 제목")), "새 제목", "2024.04"),
                arguments(Named.of("기간만", Map.of("period", "2025.01")), "제목", "2025.01"));

    }

    static Stream<Arguments> bodiesWithCurrentValues() {

        return Stream.of(
                arguments(Named.of("같은 제목", "{\"title\":\"제목\"}")),
                arguments(Named.of("같은 기간", "{\"period\":\"2024.04\"}")),
                arguments(Named.of("같은 제목과 기간", "{\"title\":\"제목\",\"period\":\"2024.04\"}")));

    }

    static Stream<Arguments> editsWithOneChangedField() {

        return Stream.of(
                arguments(Named.of("같은 제목 + 새 기간", "{\"title\":\"제목\",\"period\":\"2025.01\"}"), "제목", "2025.01"),
                arguments(Named.of("새 제목 + 같은 기간", "{\"title\":\"새 제목\",\"period\":\"2024.04\"}"), "새 제목", "2024.04"),
                arguments(Named.of("앞뒤 공백만 다른 제목", "{\"title\":\" 제목 \"}"), " 제목 ", "2024.04"),
                arguments(Named.of("앞뒤 공백만 다른 기간", "{\"period\":\"2024.04 \"}"), "제목", "2024.04 "));

    }

    static Stream<Arguments> editsWithNul() {

        return Stream.of(
                arguments(Named.of("제목 가운데", Map.of("title", "로그인\u0000개선"))),
                arguments(Named.of("기간 맨 앞", Map.of("period", "\u00002024.04"))),
                arguments(Named.of("바른 제목 + 기간 가운데", Map.of("title", "새 제목", "period", "2024\u0000.04"))));

    }

    static Stream<Arguments> bodiesWithNothingToChange() {

        return Stream.of(
                arguments(Named.of("빈 본문", "{}")),
                arguments(Named.of("둘 다 null", "{\"title\":null,\"period\":null}")));

    }

    static Stream<Arguments> invalidTitles() {

        return Stream.of(
                arguments(Named.of("빈 글", "")),
                arguments(Named.of("스페이스", "   ")),
                arguments(Named.of("탭·줄바꿈", "\t\n")),
                arguments(Named.of("257자", "가".repeat(257))));

    }

    private Long draftCard(UUID publicId, Long userId, String title, String period) {

        Long cardId = fixtures.insertCard(publicId, userId);
        jdbc.update("UPDATE card SET title = ?, period = ? WHERE id = ?", title, period, cardId);

        return cardId;

    }

    private void age(Long cardId) {

        OffsetDateTime old = OffsetDateTime.ofInstant(OLD, ZoneOffset.UTC);
        jdbc.update("UPDATE card SET updated_at = ? WHERE id = ?", old, cardId);
        jdbc.update("UPDATE card_version SET updated_at = ? WHERE card_id = ?", old, cardId);

    }

    private ResultActions edit(UUID publicId, String body) throws Exception {

        return mvc.perform(editRequest(publicId.toString(), body).with(loggedInAs(myUserId)).with(csrf()));

    }

    private MockHttpServletRequestBuilder editRequest(String id, String body) {

        return patch("/api/cards/{id}/metadata", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);

    }

    // 화면처럼 바꿀 칸만 키로 싣는다
    private String json(Object... keysAndValues) {

        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            body.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }

        return mapper.writeValueAsString(body);

    }

    private List<Map<String, Object>> stateOf(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList(
                "SELECT title, period, status, confirmed_at, updated_at FROM card WHERE id = ?", cardId));
        rows.addAll(contentOf(cardId));

        return rows;

    }

    private List<Map<String, Object>> contentOf(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList(
                "SELECT current_version, mask_rules::text AS mask_rules FROM card WHERE id = ?", cardId));
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

    private List<String> titleAndPeriodOf(Long cardId) {

        Map<String, Object> row = jdbc.queryForMap("SELECT title, period FROM card WHERE id = ?", cardId);

        return Arrays.asList((String) row.get("title"), (String) row.get("period"));

    }

    private Instant cardUpdatedAt(Long cardId) {

        return jdbc.queryForObject("SELECT updated_at FROM card WHERE id = ?", OffsetDateTime.class, cardId).toInstant();

    }

    private Instant updatedAtIn(MvcResult edited) throws Exception {

        return Instant.parse(JsonPath.read(edited.getResponse().getContentAsString(), "$.data.updatedAt"));

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

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
