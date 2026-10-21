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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
class MatchListApiTest {

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
    private Long myStatementId;
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
    @DisplayName("로그인하지 않고 조회하면 401 UNAUTHENTICATED 이다")
    void requiresLogin() throws Exception {

        mvc.perform(get("/api/matches"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    @Test
    @DisplayName("확정 카드가 없으면 AI 를 부르지 않고 fit 은 null, met 은 0 이다")
    void withoutConfirmedCardsFitIsNull() throws Exception {

        seedExample();

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].fit").value(nullValue()))
                .andExpect(jsonPath("$.data[0].required.total").value(3))
                .andExpect(jsonPath("$.data[0].required.met").value(0))
                .andExpect(jsonPath("$.data[0].preferred.met").value(0));
        verify(search, never()).search(any());

    }

    @Test
    @DisplayName("확정 카드는 있는데 행동·결과 문장이 없으면 AI 를 부르지 않고 모든 공고가 D 다")
    void confirmedCardWithoutActionAndResultIsGradeD() throws Exception {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "S", "상황", "USER_STATED", null);
        fixtures.confirmCard(card);
        seedExample();

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].fit", contains("D", "D", "D", "D", "D")));
        verify(search, never()).search(any());

    }

    @Test
    @DisplayName("등급 순으로 정렬하고 같은 등급은 마감이 가까운 순이며 상시 채용은 뒤다")
    void gradesAndSortsPostings() throws Exception {

        confirmedCardWithStatements();
        answerWith(seedExample());

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].company", contains("가온소프트", "나래랩스", "다온페이", "라온모빌리티", "마루데이터")))
                .andExpect(jsonPath("$.data[*].fit", contains("A", "B", "B", "C", "D")))
                .andExpect(jsonPath("$.data[0].required.met").value(3))
                .andExpect(jsonPath("$.data[0].preferred.met").value(1));

    }

    @Test
    @DisplayName("같은 조건에 문장이 여럿 맞아도 조건은 한 번만 센다")
    void countsEachConditionOnceAndIgnoresPreferred() throws Exception {

        confirmedCardWithStatements();
        Long posting = posting("가온소프트", "백엔드", "BACKEND", "UNSPECIFIED", null, null);
        List<Long> required = criteria(posting, "REQUIRED", 2);
        List<CriterionMatch> matches = List.of(new CriterionMatch(required.get(0), myStatementId, 0.1),
                new CriterionMatch(required.get(0), myStatementId + 1, 0.2));
        when(search.search(any())).thenReturn(new MatchingSearchResult(matches, 5));

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(jsonPath("$.data[0].required.met").value(1));

    }

    @Test
    @DisplayName("활성이고 마감 전이며 조건이 READY 로 색인에 반영된 공고만, 등급을 셀 필수·우대 조건이 있을 때 목록에 넣는다")
    void listsOnlyMatchablePostings() throws Exception {

        confirmedCardWithStatements();
        Long listed = posting("목록에 있는 회사", "백엔드", "BACKEND", "UNSPECIFIED", null, null);
        criteria(listed, "REQUIRED", 1);
        excluded("UPDATE job_posting SET status = 'EXPIRED' WHERE id = ?");
        excluded("UPDATE job_posting SET status = 'REMOVED' WHERE id = ?");
        excluded("UPDATE job_posting SET expires_at = now() - interval '1 hour' WHERE id = ?");
        excluded("UPDATE job_posting SET content_status = 'PENDING' WHERE id = ?");
        excluded("UPDATE job_posting SET content_status = 'METADATA_ONLY' WHERE id = ?");
        excluded("UPDATE job_posting SET indexed_content_hash = '" + "b".repeat(64) + "' WHERE id = ?");
        excluded("UPDATE job_posting SET indexed_content_hash = NULL WHERE id = ?");
        excluded("DELETE FROM job_posting_criterion WHERE job_posting_id = ?");
        excluded("UPDATE job_posting_criterion SET type = 'RESPONSIBILITY' WHERE job_posting_id = ?");
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 5));

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].company").value("목록에 있는 회사"));

    }

    @Test
    @DisplayName("목록에 없는 공고의 조건이나 등급을 세지 않는 조건, 모르는 조건 id 의 결과는 무시한다")
    void ignoresMatchesOfUnknownOrUngradedCriteria() throws Exception {

        confirmedCardWithStatements();
        Long listed = posting("목록에 있는 회사", "백엔드", "BACKEND", "UNSPECIFIED", null, null);
        criteria(listed, "REQUIRED", 2);
        List<Long> duties = criteria(listed, "RESPONSIBILITY", 1);
        Long hidden = posting("마감된 회사", "백엔드", "BACKEND", "UNSPECIFIED", null, null);
        List<Long> hiddenCriteria = criteria(hidden, "REQUIRED", 1);
        jdbc.update("UPDATE job_posting SET status = 'EXPIRED' WHERE id = ?", hidden);
        List<CriterionMatch> matches = new ArrayList<>();
        matches.add(new CriterionMatch(duties.get(0), myStatementId, 0.1));
        matches.add(new CriterionMatch(hiddenCriteria.get(0), myStatementId, 0.1));
        matches.add(new CriterionMatch(987654321L, myStatementId, 0.1));
        when(search.search(any())).thenReturn(new MatchingSearchResult(matches, 5));

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].required.met").value(0))
                .andExpect(jsonPath("$.data[0].fit").value("D"));

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
        criteria(posting("가온소프트", "백엔드", "BACKEND", "UNSPECIFIED", null, null), "REQUIRED", 1);
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 5));

        mvc.perform(get("/api/matches").with(loggedIn())).andExpect(status().isOk());

        ArgumentCaptor<MatchingSearchRequest> sent = ArgumentCaptor.forClass(MatchingSearchRequest.class);
        verify(search).search(sent.capture());
        assertThat(sent.getValue().statements()).hasSize(200);

    }

    @Test
    @DisplayName("AI 가 거절하는 문장은 보내지 않는다: 공백만 남은 문장은 빼고 4000자를 넘는 문장은 4000자로 자른다")
    void doesNotSendWhatAiRejects() throws Exception {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", "", "USER_STATED", null);
        fixtures.insertStatement(card, 1, "R", "가".repeat(100), "USER_STATED", null);
        fixtures.confirmCard(card);
        jdbc.update("UPDATE card SET mask_rules = ?::jsonb WHERE id = ?",
                "[{\"from\":\"가\",\"to\":\"" + "나".repeat(100) + "\"}]", card);
        criteria(posting("가온소프트", "백엔드", "BACKEND", "UNSPECIFIED", null, null), "REQUIRED", 1);
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 5));

        mvc.perform(get("/api/matches").with(loggedIn())).andExpect(status().isOk());

        ArgumentCaptor<MatchingSearchRequest> sent = ArgumentCaptor.forClass(MatchingSearchRequest.class);
        verify(search).search(sent.capture());
        assertThat(sent.getValue().statements()).hasSize(1);
        assertThat(sent.getValue().statements().getFirst().body()).hasSize(4000);

    }

    @Test
    @DisplayName("AI 색인에 조건이 하나도 없다고 답하면 근거 없는 D 로 보이지 않게 503 AI_UNAVAILABLE 이다")
    void emptyIndexIsServiceUnavailable() throws Exception {

        confirmedCardWithStatements();
        seedExample();
        when(search.search(any())).thenReturn(new MatchingSearchResult(List.of(), 0));

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

    }

    @ParameterizedTest
    @CsvSource({"true, 503, AI_UNAVAILABLE", "false, 500, INTERNAL_ERROR"})
    @DisplayName("AI 검색이 다시 시도할 수 있게 실패하면 503, 다시 해도 같은 실패이거나 이상한 응답이면 500 이다")
    void searchFailureMapsToStatus(boolean retryable, int status, String code) throws Exception {

        confirmedCardWithStatements();
        seedExample();
        when(search.search(any())).thenThrow(new MatchingSearchUnavailableException("AI 매칭 검색 실패", retryable,
                !retryable, retryable ? "AI_UNAVAILABLE" : "AI_INVALID_RESPONSE", null));

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code));

    }

    @Test
    @DisplayName("응답의 칸 이름과 중첩이 FE 계약 예시와 같다")
    void responseShapeMatchesContractExamples() throws Exception {

        confirmedCardWithStatements();
        answerWith(seedExample());
        JsonNode graded = MAPPER.readTree(mvc.perform(get("/api/matches").with(loggedIn())).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        jdbc.update("UPDATE card SET status = 'DRAFT', confirmed_at = NULL");
        JsonNode withoutCards = MAPPER.readTree(mvc.perform(get("/api/matches").with(loggedIn())).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        assertSameShape(example("list"), graded, "list");
        assertSameShape(example("list-no-cards"), withoutCards, "list-no-cards");

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

        try (InputStream in = MatchListApiTest.class.getResourceAsStream("/matching/examples/" + name + ".json")) {
            return MAPPER.readTree(in);
        }

    }

    private void confirmedCardWithStatements() {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        myStatementId = fixtures.insertStatement(card, 1, "A", "Spring Boot API 응답 시간을 개선했다", "USER_STATED", null);
        fixtures.insertStatement(card, 1, "R", "응답 시간이 40% 줄었다", "USER_STATED", null);
        fixtures.confirmCard(card);

    }

    /** FE 계약 예시(list.json)와 같은 다섯 공고를 일부러 뒤섞은 순서로 넣고, 맞은 조건 id 를 돌려준다 */
    private Set<Long> seedExample() {

        Set<Long> met = new HashSet<>();
        met.addAll(withConditions(posting("다온페이", "플랫폼 엔지니어", "DEVOPS", "UNSPECIFIED", null, null), 0, 0, 2, 1));
        met.addAll(withConditions(posting("마루데이터", "데이터 엔지니어", "DATA", "EXPERIENCED", 2, null), 2, 0, 1, 0));
        met.addAll(withConditions(posting("라온모빌리티", "안드로이드 개발자", "MOBILE", "EXPERIENCED", 3, days(14)), 3, 1, 2, 0));
        met.addAll(withConditions(posting("나래랩스", "서버 개발자", "BACKEND", "ENTRY", null, days(30)), 4, 2, 2, 1));
        met.addAll(withConditions(posting("가온소프트", "백엔드 엔지니어", "BACKEND", "ENTRY", null, days(12)), 3, 3, 2, 1));

        return met;

    }

    private Set<Long> withConditions(Long posting, int required, int requiredMet, int preferred, int preferredMet) {

        Set<Long> met = new HashSet<>();
        met.addAll(criteria(posting, "REQUIRED", required).subList(0, requiredMet));
        met.addAll(criteria(posting, "PREFERRED", preferred).subList(0, preferredMet));

        return met;

    }

    private void answerWith(Set<Long> metCriterionIds) {

        List<CriterionMatch> matches = metCriterionIds.stream()
                .map(id -> new CriterionMatch(id, myStatementId, 0.2)).toList();
        when(search.search(any())).thenReturn(new MatchingSearchResult(matches, 20));

    }

    private void excluded(String change) {

        Long posting = posting("제외될 회사 " + postingSeq, "백엔드", "BACKEND", "UNSPECIFIED", null, null);
        criteria(posting, "REQUIRED", 1);
        jdbc.update(change, posting);

    }

    private Long posting(String company, String role, String category, String level, Integer minYears, Instant expiresAt) {

        postingSeq++;
        String quote = "UNSPECIFIED".equals(level) ? null : "경력 원문";

        return jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, category,
                    experience_level, experience_min_years, experience_quote, status, content_status, content_hash,
                    indexed_content_hash, expires_at, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'READY', ?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, "company" + postingSeq, "posting-" + postingSeq, company, role,
                "https://careers.example.com/jobs/" + postingSeq, category, level, minYears, quote, HASH, HASH,
                expiresAt == null ? null : Timestamp.from(expiresAt));

    }

    private List<Long> criteria(Long posting, String type, int count) {

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(jdbc.queryForObject("""
                    INSERT INTO job_posting_criterion (job_posting_id, type, seq, text, quote)
                    VALUES (?, ?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM job_posting_criterion WHERE job_posting_id = ?),
                            '조건', '원문 인용')
                    RETURNING id
                    """, Long.class, posting, type, posting));
        }

        return ids;

    }

    private static Instant days(int days) {

        return Instant.now().plus(days, ChronoUnit.DAYS);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
