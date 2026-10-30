package com.gitory.backend.matching.api;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.agent.port.JobMatchingSearchPort;
import com.gitory.backend.agent.port.MatchingSearchRequest;
import com.gitory.backend.agent.port.MatchingSearchResult;
import com.gitory.backend.agent.port.MatchingSearchUnavailableException;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class MatchDetailApiTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String HASH = "a".repeat(64);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JobMatchingSearchPort search;

    private TestFixtures fixtures;
    private Long myUserId;
    private int postingSeq;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        myUserId = fixtures.insertUser(1L, "grow22");
        postingSeq = 0;

    }

    @Test
    @DisplayName("조건은 필수 → 우대 → 담당 업무 순이고 같은 종류 안에서는 원문 순서다")
    void ordersConditionsByTypeThenSeq() throws Exception {

        confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        criterion(posting, "RESPONSIBILITY", 5, "업무");
        criterion(posting, "REQUIRED", 9, "필수 둘");
        criterion(posting, "PREFERRED", 2, "우대");
        criterion(posting, "REQUIRED", 3, "필수 하나");
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 4));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[*].text", contains("필수 하나", "필수 둘", "우대", "업무")))
                .andExpect(jsonPath("$.data.conditions[*].type",
                        contains("REQUIRED", "REQUIRED", "PREFERRED", "RESPONSIBILITY")));

    }

    @Test
    @DisplayName("근거 문장은 거리가 가까운 순이고 같은 카드의 행동·결과가 둘 다 맞으면 둘 다 담는다")
    void supportsAreClosestFirstAndKeepBothSlotsOfOneCard() throws Exception {

        Long card = confirmedCard("행동 문장", "결과 문장");
        Long posting = posting(days(10));
        Long matched = criterion(posting, "REQUIRED", "맞은 조건");
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(
                new CriterionMatch(matched, statementOf(card, "A"), 0.30),
                new CriterionMatch(matched, statementOf(card, "R"), 0.10)), 1));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[0].supports[*].field", contains("R", "A")))
                .andExpect(jsonPath("$.data.conditions[0].supports[*].sentence", contains("결과 문장", "행동 문장")));

    }

    @Test
    @DisplayName("근거가 없는 조건도 숨기지 않고 빈 근거 배열로 담는다")
    void conditionsWithoutEvidenceStayWithEmptySupports() throws Exception {

        Long card = confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        Long matched = criterion(posting, "REQUIRED", "맞은 조건");
        criterion(posting, "REQUIRED", "빈 조건");
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(
                new CriterionMatch(matched, statementOf(card, "A"), 0.10)), 2));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[1].text").value("빈 조건"))
                .andExpect(jsonPath("$.data.conditions[1].supports", empty()));

    }

    @Test
    @DisplayName("근거 문장마다 그 문장이 속한 카드의 공개 id·제목·종류를 담는다")
    void supportsCarryTheirCardIdentity() throws Exception {

        Long card = confirmedCard("행동", "결과");
        jdbc.update("UPDATE card SET title = '동시 실행 설계', card_type = 'TECH' WHERE id = ?", card);
        Long posting = posting(days(10));
        Long matched = criterion(posting, "REQUIRED", "맞은 조건");
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(
                new CriterionMatch(matched, statementOf(card, "A"), 0.10)), 1));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[0].supports[0].cardId").value(publicIdOfCard(card)))
                .andExpect(jsonPath("$.data.conditions[0].supports[0].cardTitle").value("동시 실행 설계"))
                .andExpect(jsonPath("$.data.conditions[0].supports[0].cardKind").value("TECH"));

    }

    @Test
    @DisplayName("등급은 필수 조건으로 세고, 우대는 따로 세며, 담당 업무는 세지 않는다")
    void countsRequiredAndPreferredButNotDuties() throws Exception {

        Long card = confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        Long first = criterion(posting, "REQUIRED", "필수 하나");
        criterion(posting, "REQUIRED", "필수 둘");
        Long preferred = criterion(posting, "PREFERRED", "우대");
        Long duty = criterion(posting, "RESPONSIBILITY", "업무");
        Long statement = statementOf(card, "A");
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(
                new CriterionMatch(first, statement, 0.1), new CriterionMatch(preferred, statement, 0.1),
                new CriterionMatch(duty, statement, 0.1)), 4));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fit").value("B"))
                .andExpect(jsonPath("$.data.required.total").value(2))
                .andExpect(jsonPath("$.data.required.met").value(1))
                .andExpect(jsonPath("$.data.preferred.total").value(1))
                .andExpect(jsonPath("$.data.preferred.met").value(1));

    }

    @Test
    @DisplayName("같은 조건에 문장이 여럿 맞아도 조건은 한 번만 센다")
    void countsEachConditionOnce() throws Exception {

        Long card = confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        List<Long> required = criteria(posting, "REQUIRED", 2);
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(
                new CriterionMatch(required.get(0), statementOf(card, "A"), 0.1),
                new CriterionMatch(required.get(0), statementOf(card, "R"), 0.2)), 5));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.required.met").value(1));

    }

    @Test
    @DisplayName("확정 카드는 있는데 행동·결과 문장이 없으면 AI 를 부르지 않고 D 다")
    void confirmedCardWithoutActionAndResultIsGradeD() throws Exception {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "S", "상황", "USER_STATED", null);
        fixtures.confirmCard(card);
        Long posting = posting(days(10));
        criteria(posting, "REQUIRED", 2);

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fit").value("D"));
        verify(search, never()).search(any());

    }

    @Test
    @DisplayName("문장이 200개를 넘으면 200개만 AI 에 보낸다")
    void sendsAtMost200StatementsNewestFirst() throws Exception {

        IntStream.range(0, 101).forEach(index -> {
            Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
            fixtures.insertStatement(card, 1, "A", "행동 " + index, "USER_STATED", null);
            fixtures.insertStatement(card, 1, "R", "결과 " + index, "USER_STATED", null);
            fixtures.confirmCard(card);
            jdbc.update("UPDATE card SET confirmed_at = now() + (? * interval '1 minute') WHERE id = ?", index, card);
        });
        Long posting = posting(days(10));
        criteria(posting, "REQUIRED", 1);
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 5));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn())).andExpect(status().isOk());

        ArgumentCaptor<MatchingSearchRequest> sent = ArgumentCaptor.forClass(MatchingSearchRequest.class);
        verify(search).search(sent.capture());
        assertThat(sent.getValue().statements()).hasSize(200);

    }

    @Test
    @DisplayName("AI 가 거절하는 문장은 보내지 않는다: 공백만 남은 문장은 빼고 4000자를 넘는 문장은 4000자로 자른다")
    void doesNotSendWhatAiRejects() throws Exception {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", "", "USER_STATED", null);
        fixtures.insertStatement(card, 1, "R", "가".repeat(100), "USER_STATED", null);
        fixtures.confirmCard(card);
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?",
                "[{\"from\":\"가\",\"to\":\"" + "나".repeat(100) + "\"}]", card);
        Long posting = posting(days(10));
        criteria(posting, "REQUIRED", 1);
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 5));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn())).andExpect(status().isOk());

        ArgumentCaptor<MatchingSearchRequest> sent = ArgumentCaptor.forClass(MatchingSearchRequest.class);
        verify(search).search(sent.capture());
        assertThat(sent.getValue().statements()).hasSize(1);
        assertThat(sent.getValue().statements().getFirst().body()).hasSize(4000);

    }

    @Test
    @DisplayName("AI 색인에 조건이 하나도 없다고 답하면 근거 없는 D 로 보이지 않게 503 AI_UNAVAILABLE 이다")
    void emptyIndexIsServiceUnavailable() throws Exception {

        confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        criteria(posting, "REQUIRED", 1);
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 0));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

    }

    @ParameterizedTest
    @CsvSource({"true, 503, AI_UNAVAILABLE", "false, 500, INTERNAL_ERROR"})
    @DisplayName("AI 검색이 다시 시도할 수 있게 실패하면 503, 다시 해도 같은 실패이거나 이상한 응답이면 500 이다")
    void searchFailureMapsToStatus(boolean retryable, int status, String code) throws Exception {

        confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        criteria(posting, "REQUIRED", 1);
        when(search.search(any())).thenThrow(new MatchingSearchUnavailableException("AI 매칭 검색 실패", retryable,
                !retryable, retryable ? "AI_UNAVAILABLE" : "AI_INVALID_RESPONSE", null));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code));

    }

    @Test
    @DisplayName("확정 카드가 없으면 AI 를 부르지 않고 fit 은 null 이며 근거는 모두 빈 배열이다")
    void withoutConfirmedCardsFitIsNullAndSupportsEmpty() throws Exception {

        Long posting = posting(days(10));
        criterion(posting, "REQUIRED", "필수");
        criterion(posting, "RESPONSIBILITY", "업무");

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fit").value(nullValue()))
                .andExpect(jsonPath("$.data.required.met").value(0))
                .andExpect(jsonPath("$.data.conditions[*].supports", contains(empty(), empty())));
        verify(search, never()).search(any());

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE job_posting SET status = 'EXPIRED' WHERE id = ?",
            "UPDATE job_posting SET status = 'REMOVED' WHERE id = ?",
            "UPDATE job_posting SET expires_at = now() - interval '1 hour' WHERE id = ?",
            "UPDATE job_posting SET content_status = 'PENDING' WHERE id = ?",
            "UPDATE job_posting SET content_status = 'METADATA_ONLY' WHERE id = ?",
            "UPDATE job_posting SET indexed_content_hash = repeat('b', 64) WHERE id = ?",
            "UPDATE job_posting SET indexed_content_hash = NULL WHERE id = ?",
            "UPDATE job_posting_criterion SET type = 'RESPONSIBILITY' WHERE job_posting_id = ?"
    })
    @DisplayName("마감·사라짐·본문 없음·색인 안 됨·등급 조건 없음처럼 목록에 없는 공고는 AI 를 부르지 않고 404 NOT_FOUND 이다")
    void postingsOutsideTheListAreNotFound(String change) throws Exception {

        confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        criterion(posting, "REQUIRED", "필수");
        jdbc.update(change, posting);

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        verify(search, never()).search(any());

    }

    @Test
    @DisplayName("마감이 없는 상시 채용 공고도 상세를 볼 수 있다")
    void openEndedPostingIsFound() throws Exception {

        Long card = confirmedCard("행동", "결과");
        Long posting = posting(null);
        Long required = criterion(posting, "REQUIRED", "필수");
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(
                new CriterionMatch(required, statementOf(card, "A"), 0.1)), 1));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source.expiresAt").value(nullValue()))
                .andExpect(jsonPath("$.data.fit").value("A"));

    }

    @Test
    @DisplayName("UUID 가 아닌 id 는 404 NOT_FOUND 이다")
    void malformedIdIsNotFound() throws Exception {

        mvc.perform(get("/api/matches/not-a-uuid").with(loggedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

    }

    @Test
    @DisplayName("응답의 칸 이름과 중첩이 FE 계약 예시(detail.json)와 같다")
    void responseShapeMatchesContractExample() throws Exception {

        Long first = confirmedCard("행동 하나", "결과 하나");
        Long second = confirmedCard("행동 둘", "결과 둘");
        Long third = confirmedCard("행동 셋", "결과 셋");
        Long posting = posting(days(30));
        List<Long> required = criteria(posting, "REQUIRED", 4);
        List<Long> preferred = criteria(posting, "PREFERRED", 2);
        List<Long> duties = criteria(posting, "RESPONSIBILITY", 2);
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(
                new CriterionMatch(required.get(0), statementOf(first, "A"), 0.1),
                new CriterionMatch(required.get(1), statementOf(second, "A"), 0.1),
                new CriterionMatch(required.get(1), statementOf(second, "R"), 0.2),
                new CriterionMatch(preferred.get(0), statementOf(third, "A"), 0.1),
                new CriterionMatch(duties.get(1), statementOf(third, "R"), 0.1)), 8));

        JsonNode actual = MAPPER.readTree(mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertSameShape(example("detail"), actual, "detail");

    }

    @Test
    @DisplayName("오류 응답이 errors.json 과 같은 모양이고 코드가 같다")
    void errorsMatchContractExample() throws Exception {

        JsonNode errors = example("errors");
        confirmedCard("행동", "결과");
        Long posting = posting(days(10));
        criterion(posting, "REQUIRED", "필수");
        when(search.search(any())).thenThrow(new MatchingSearchUnavailableException("AI 매칭 검색 실패", true, false,
                "AI_UNAVAILABLE", null));

        assertError(errors.get("401"), get("/api/matches/" + publicIdOf(posting)), 401);
        assertError(errors.get("404"), get("/api/matches/" + UUID.randomUUID()).with(loggedIn()), 404);
        assertError(errors.get("503"), get("/api/matches/" + publicIdOf(posting)).with(loggedIn()), 503);

    }

    private void assertError(JsonNode expected, MockHttpServletRequestBuilder request, int status) throws Exception {

        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode actual = MAPPER.readTree(response.getContentAsString(StandardCharsets.UTF_8));

        assertThat(response.getStatus()).isEqualTo(status);
        assertSameShape(expected, actual, "error " + status);
        assertThat(actual.get("error").get("code")).isEqualTo(expected.get("error").get("code"));

    }

    /** 값이 아니라 모양을 비교한다: 객체는 칸 이름이 같고, 배열은 같은 위치의 원소끼리 보며, null 인 자리와 값 종류가 같아야 한다 */
    private static void assertSameShape(JsonNode expected, JsonNode actual, String path) {

        if (expected.isObject()) {
            assertThat(actual.isObject()).as(path + " 는 객체").isTrue();
            assertThat(actual.propertyNames()).as(path + " 의 칸").containsExactlyInAnyOrderElementsOf(expected.propertyNames());
            expected.propertyNames().forEach(name -> assertSameShape(expected.get(name), actual.get(name), path + "." + name));
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as(path + " 는 배열").isTrue();
            assertThat(actual.size()).as(path + " 의 길이").isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertSameShape(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        } else {
            assertThat(actual.isNull()).as(path + " 의 null 여부").isEqualTo(expected.isNull());
            assertThat(actual.getNodeType()).as(path + " 의 값 종류").isEqualTo(expected.getNodeType());
        }

    }

    private static JsonNode example(String name) throws Exception {

        try (InputStream in = MatchDetailApiTest.class.getResourceAsStream("/matching/examples/" + name + ".json")) {
            return MAPPER.readTree(in);
        }

    }

    private Long confirmedCard(String action, String result) {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", action, "USER_STATED", null);
        fixtures.insertStatement(card, 1, "R", result, "USER_STATED", null);
        fixtures.confirmCard(card);

        return card;

    }

    private Long statementOf(Long card, String slot) {

        return jdbc.queryForObject("SELECT id FROM card_statement WHERE card_id = ? AND star_slot = ?", Long.class,
                card, slot);

    }

    private String publicIdOfCard(Long card) {

        return jdbc.queryForObject("SELECT public_id::text FROM card WHERE id = ?", String.class, card);

    }

    private String publicIdOf(Long posting) {

        return jdbc.queryForObject("SELECT public_id::text FROM job_posting WHERE id = ?", String.class, posting);

    }

    private Long posting(Instant expiresAt) {

        postingSeq++;

        return jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, category,
                    experience_level, experience_quote, status, content_status, content_hash,
                    indexed_content_hash, expires_at, first_seen_at, last_seen_at)
                VALUES (?, ?, '나래랩스', '서버 개발자', ?, 'BACKEND', 'ENTRY', '신입 지원 가능', 'ACTIVE', 'READY', ?, ?, ?,
                        now(), now())
                RETURNING id
                """, Long.class, "company" + postingSeq, "posting-" + postingSeq,
                "https://careers.example.com/jobs/" + postingSeq, HASH, HASH,
                expiresAt == null ? null : Timestamp.from(expiresAt));

    }

    private Long criterion(Long posting, String type, String text) {

        int seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM job_posting_criterion WHERE job_posting_id = ?", Integer.class,
                posting);

        return criterion(posting, type, seq, text);

    }

    private Long criterion(Long posting, String type, int seq, String text) {

        return jdbc.queryForObject("""
                INSERT INTO job_posting_criterion (job_posting_id, type, seq, text, quote)
                VALUES (?, ?, ?, ?, '원문 인용')
                RETURNING id
                """, Long.class, posting, type, seq, text);

    }

    private List<Long> criteria(Long posting, String type, int count) {

        return IntStream.range(0, count).mapToObj(i -> criterion(posting, type, "조건")).toList();

    }

    private static Instant days(int days) {

        return Instant.now().plus(days, ChronoUnit.DAYS);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
