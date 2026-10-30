package com.gitory.backend.matching.api;

import com.gitory.backend.agent.port.JobMatchingSearchPort;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.matching.sources[0].key=gaon",
        "gitory.matching.sources[0].name=가온소프트",
        "gitory.matching.sources[0].type=GREENHOUSE",
        "gitory.matching.sources[0].board=gaon",
        "gitory.matching.sources[0].logo=https://cdn.example.com/gaon.png",
        "gitory.matching.sources[1].key=rocketpunch",
        "gitory.matching.sources[1].name=로켓펀치",
        "gitory.matching.sources[1].type=ROCKETPUNCH",
        "gitory.matching.sources[1].board=https://openapi.rocketpunch.com"
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
    @DisplayName("확정 카드가 있어도 AI 를 부르지 않고 모든 공고의 fit 은 null, met 은 0 이며 total 은 공고의 조건 수다")
    void neverCallsAiAndLeavesFitEmpty() throws Exception {

        confirmedCardWithStatements();
        seedExample();

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[*].fit", contains(nullValue(), nullValue(), nullValue(), nullValue(), nullValue())))
                .andExpect(jsonPath("$.data[?(@.company == '가온소프트')].required.total", contains(3)))
                .andExpect(jsonPath("$.data[*].required.met", contains(0, 0, 0, 0, 0)))
                .andExpect(jsonPath("$.data[*].preferred.met", contains(0, 0, 0, 0, 0)));
        verify(search, never()).search(any());

    }

    @Test
    @DisplayName("등급 없이 마감이 가까운 공고가 앞이고 마감일이 없는 공고는 뒤다")
    void sortsByDeadlineWithoutGrades() throws Exception {

        seedExample();

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0:3].company", contains("가온소프트", "라온모빌리티", "나래랩스")))
                .andExpect(jsonPath("$.data[3:5].company", containsInAnyOrder("다온페이", "마루데이터")));

    }

    @Test
    @DisplayName("활성이고 마감 전이며 조건이 READY 로 색인에 반영된 공고만, 필수·우대 조건이 있을 때 목록에 넣는다")
    void listsOnlyMatchablePostings() throws Exception {

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

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].company").value("목록에 있는 회사"));

    }

    @Test
    @DisplayName("공고마다 회사 설정의 키를 companyId 로, 로고 주소를 logoUrl 로 싣고, 여러 회사를 주는 출처는 회사 이름을 붙이고 로고는 null 이다")
    void carriesCompanyIdAndLogoFromCompanySettings() throws Exception {

        criteria(postingAt("gaon", "가온소프트"), "REQUIRED", 1);
        criteria(postingAt("rocketpunch", "블루랩"), "REQUIRED", 1);
        criteria(postingAt("unlisted", "설정에 없는 회사"), "REQUIRED", 1);

        mvc.perform(get("/api/matches").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.company == '가온소프트')].companyId", contains("gaon")))
                .andExpect(jsonPath("$.data[?(@.company == '가온소프트')].logoUrl", contains("https://cdn.example.com/gaon.png")))
                .andExpect(jsonPath("$.data[?(@.company == '블루랩')].companyId", contains("rocketpunch:블루랩")))
                .andExpect(jsonPath("$.data[?(@.company == '블루랩')].logoUrl", contains(nullValue())))
                .andExpect(jsonPath("$.data[?(@.company == '설정에 없는 회사')].companyId", contains("unlisted")))
                .andExpect(jsonPath("$.data[?(@.company == '설정에 없는 회사')].logoUrl", contains(nullValue())));

    }

    @Test
    @DisplayName("companyId 를 주면 그 회사 공고만 돌려주고, 없는 회사면 빈 목록이며, 비어 있으면 거르지 않는다")
    void filtersByCompanyId() throws Exception {

        criteria(postingAt("gaon", "가온소프트"), "REQUIRED", 1);
        criteria(postingAt("rocketpunch", "블루랩"), "REQUIRED", 1);

        mvc.perform(get("/api/matches").param("companyId", "rocketpunch:블루랩").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].company", contains("블루랩")));
        mvc.perform(get("/api/matches").param("companyId", "").with(loggedIn()))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/matches").param("companyId", "nope").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

    }

    @Test
    @DisplayName("확정 카드가 있어도 응답의 칸 이름과 중첩이 FE 계약 예시(list-no-cards.json)와 같다")
    void responseShapeMatchesContractExample() throws Exception {

        confirmedCardWithStatements();
        seedExample();
        JsonNode listed = MAPPER.readTree(mvc.perform(get("/api/matches").with(loggedIn())).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        assertSameShape(example("list-no-cards"), listed, "list-no-cards");

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
        fixtures.insertStatement(card, 1, "A", "Spring Boot API 응답 시간을 개선했다", "USER_STATED", null);
        fixtures.insertStatement(card, 1, "R", "응답 시간이 40% 줄었다", "USER_STATED", null);
        fixtures.confirmCard(card);

    }

    /** FE 계약 예시(list.json)와 같은 다섯 공고를 일부러 뒤섞은 순서로 넣는다 */
    private void seedExample() {

        withConditions(posting("다온페이", "플랫폼 엔지니어", "DEVOPS", "UNSPECIFIED", null, null), 0, 2);
        withConditions(posting("마루데이터", "데이터 엔지니어", "DATA", "EXPERIENCED", 2, null), 2, 1);
        withConditions(posting("라온모빌리티", "안드로이드 개발자", "MOBILE", "EXPERIENCED", 3, days(14)), 3, 2);
        withConditions(posting("나래랩스", "서버 개발자", "BACKEND", "ENTRY", null, days(30)), 4, 2);
        withConditions(posting("가온소프트", "백엔드 엔지니어", "BACKEND", "ENTRY", null, days(12)), 3, 2);

    }

    private void withConditions(Long posting, int required, int preferred) {

        criteria(posting, "REQUIRED", required);
        criteria(posting, "PREFERRED", preferred);

    }

    private void excluded(String change) {

        Long posting = posting("제외될 회사 " + postingSeq, "백엔드", "BACKEND", "UNSPECIFIED", null, null);
        criteria(posting, "REQUIRED", 1);
        jdbc.update(change, posting);

    }

    private Long posting(String company, String role, String category, String level, Integer minYears, Instant expiresAt) {

        return insertPosting("company" + (postingSeq + 1), company, role, category, level, minYears, expiresAt);

    }

    private Long postingAt(String companyKey, String company) {

        return insertPosting(companyKey, company, "백엔드", "BACKEND", "UNSPECIFIED", null, null);

    }

    private Long insertPosting(String companyKey, String company, String role, String category, String level,
                               Integer minYears, Instant expiresAt) {

        postingSeq++;
        String quote = "UNSPECIFIED".equals(level) ? null : "경력 원문";

        return jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, category,
                    experience_level, experience_min_years, experience_quote, status, content_status, content_hash,
                    indexed_content_hash, expires_at, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'READY', ?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, companyKey, "posting-" + postingSeq, company, role,
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
