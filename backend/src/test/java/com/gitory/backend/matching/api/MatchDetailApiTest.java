package com.gitory.backend.matching.api;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.JudgedCondition.ConditionSupport;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
    MatchJudgementPort port;

    @MockitoBean
    UserRateLimiter rateLimiter;

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

        confirmedCard("행동");
        Long posting = posting(days(10), null);
        criterion(posting, "RESPONSIBILITY", 5, "업무", null);
        criterion(posting, "REQUIRED", 9, "필수 둘", null);
        criterion(posting, "PREFERRED", 2, "우대", null);
        criterion(posting, "REQUIRED", 3, "필수 하나", null);

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[*].text", contains("필수 하나", "필수 둘", "우대", "업무")))
                .andExpect(jsonPath("$.data.conditions[*].type",
                        contains("REQUIRED", "REQUIRED", "PREFERRED", "RESPONSIBILITY")));

    }

    @Test
    @DisplayName("열 때는 AI 를 부르지 않고, 아직 판단하지 않은 기술·경험 조건은 UNJUDGED 이며 needsJudgement 가 true 이고 요약은 null 이다")
    void showsUnjudgedConditionsWithoutAskingAi() throws Exception {

        confirmedCard("Spring 으로 결제 API 를 만들었다");
        Long posting = posting(days(10), null);
        criterion(posting, "REQUIRED", 1, "Spring 경험", null);

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[0].status").value("UNJUDGED"))
                .andExpect(jsonPath("$.data.conditions[0].metBy", empty()))
                .andExpect(jsonPath("$.data.needsJudgement").value(true))
                .andExpect(jsonPath("$.data.summary").value(nullValue()));
        verify(port, never()).judge(any());

    }

    @Test
    @DisplayName("맞춰 보면 채운 조건은 채운 카드의 공개 id·제목·종류·레포 id·이유와 함께 MET, 아무 카드도 못 채운 조건은 NOT_MET 이고 요약은 우대·일반 조건을 빼고 기술·경험 필수 조건만 센다")
    void judgesAndShowsMetAndNotMetWithSummary() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        Long card = confirmedCard("Spring 으로 결제 API 를 만들었다");
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?",
                fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "pay")), card);
        Long posting = posting(days(10), null);
        Long spring = criterion(posting, "REQUIRED", 1, "Spring 경험", null);
        criterion(posting, "REQUIRED", 2, "Kafka 경험", null);
        criterion(posting, "REQUIRED", 3, "영어 회화", "LANGUAGE");
        criterion(posting, "PREFERRED", 4, "Redis 경험", null);
        criterion(posting, "RESPONSIBILITY", 5, "결제 서버 개발", null);
        meets(Map.of(spring, card));

        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[*].status", contains("MET", "NOT_MET", "CHECK", "NOT_MET", null)))
                .andExpect(jsonPath("$.data.conditions[*].generalType", contains(null, null, "LANGUAGE", null, null)))
                .andExpect(jsonPath("$.data.conditions[0].metBy[0].cardId").value(publicIdOfCard(card)))
                .andExpect(jsonPath("$.data.conditions[0].metBy[0].cardTitle").isString())
                .andExpect(jsonPath("$.data.conditions[0].metBy[0].cardKind").isString())
                .andExpect(jsonPath("$.data.conditions[0].metBy[0].repoId").value(repoPublicId.toString()))
                .andExpect(jsonPath("$.data.conditions[0].metBy[0].reason").value("이 카드가 채웠다"))
                .andExpect(jsonPath("$.data.conditions[1].metBy", empty()))
                .andExpect(jsonPath("$.data.needsJudgement").value(false))
                .andExpect(jsonPath("$.data.summary.requiredMet").value(1))
                .andExpect(jsonPath("$.data.summary.requiredTotal").value(2))
                .andExpect(jsonPath("$.data.required.met").value(1))
                .andExpect(jsonPath("$.data.fit").value(nullValue()));

    }

    @Test
    @DisplayName("학력 조건은 AI 에게 묻지 않고 인적사항과 견줘, 인적사항에 학력이 없으면 UNKNOWN 이고 수준을 못 읽은 학력은 CHECK 이며 요약에 세지 않는다")
    void comparesEducationConditionsWithTheProfileInsteadOfAskingAi() throws Exception {

        Long card = confirmedCard("Spring 으로 결제 API 를 만들었다");
        Long posting = posting(days(10), null);
        Long spring = criterion(posting, "REQUIRED", 1, "Spring 경험", null);
        Long bachelor = criterion(posting, "REQUIRED", 2, "학사 이상", "EDUCATION");
        jdbc.update("UPDATE job_posting_criterion SET education_level = 'BACHELOR' WHERE id = ?", bachelor);
        criterion(posting, "REQUIRED", 3, "관련 학과 졸업", "EDUCATION");
        meets(Map.of(spring, card));

        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[*].generalType", contains(null, "EDUCATION", "EDUCATION")))
                .andExpect(jsonPath("$.data.conditions[*].status", contains("MET", "UNKNOWN", "CHECK")))
                .andExpect(jsonPath("$.data.summary.requiredMet").value(1))
                .andExpect(jsonPath("$.data.summary.requiredTotal").value(1));
        ArgumentCaptor<MatchJudgementRequest> asked = ArgumentCaptor.forClass(MatchJudgementRequest.class);
        verify(port).judge(asked.capture());
        assertThat(asked.getValue().conditions()).extracting(condition -> condition.conditionId())
                .containsExactly(String.valueOf(spring));

    }

    @Test
    @DisplayName("다시 열면 저장된 판단을 그대로 보여 주고, 카드 글이 바뀌면 그 판단을 쓰지 않아 다시 UNJUDGED 다")
    void reusesStoredJudgementsUntilTheCardChanges() throws Exception {

        Long card = confirmedCard("Spring 으로 결제 API 를 만들었다");
        Long posting = posting(days(10), null);
        Long spring = criterion(posting, "REQUIRED", 1, "Spring 경험", null);
        meets(Map.of(spring, card));
        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()));

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(jsonPath("$.data.conditions[0].status").value("MET"));

        jdbc.update("UPDATE card_statement SET body = '결제 API 를 다시 만들었다' WHERE card_id = ?", card);
        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(jsonPath("$.data.conditions[0].status").value("UNJUDGED"))
                .andExpect(jsonPath("$.data.needsJudgement").value(true));

    }

    @Test
    @DisplayName("맞춰 보기는 물을 짝이 남았을 때만 AI 를 부르고 호출 한도를 세며, 다 판단한 공고는 다시 묻지도 세지도 않는다")
    void countsTheLimitOnlyWhenSomethingIsLeftToJudge() throws Exception {

        Long card = confirmedCard("Spring 으로 결제 API 를 만들었다");
        Long posting = posting(days(10), null);
        Long spring = criterion(posting, "REQUIRED", 1, "Spring 경험", null);
        meets(Map.of(spring, card));

        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()));
        verify(rateLimiter).acquire(RateLimitedAction.MATCH_JUDGEMENT, myUserId, 1);
        clearInvocations(rateLimiter, port);

        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk());
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());
        verify(port, never()).judge(any());

    }

    @Test
    @DisplayName("확정 카드가 없으면 AI 를 부르지 않고 기술·경험 조건은 모두 NOT_MET, 판단할 짝이 없어 needsJudgement 는 false 다")
    void withoutConfirmedCardsEverySkillConditionIsNotMet() throws Exception {

        Long posting = posting(days(10), null);
        criterion(posting, "REQUIRED", 1, "Spring 경험", null);

        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[0].status").value("NOT_MET"))
                .andExpect(jsonPath("$.data.needsJudgement").value(false))
                .andExpect(jsonPath("$.data.summary.requiredMet").value(0));
        verify(port, never()).judge(any());

    }

    @Test
    @DisplayName("경력 연수를 요구하는 공고는 인적사항이 없으면 경력 판단이 UNKNOWN, 요구가 없으면 null 이다")
    void showsCareerStatusAgainstTheProfile() throws Exception {

        Long experienced = posting(days(10), 3);
        criterion(experienced, "REQUIRED", 1, "Spring 경험", null);
        Long entry = posting(days(10), null);
        criterion(entry, "REQUIRED", 1, "Spring 경험", null);

        mvc.perform(get("/api/matches/" + publicIdOf(experienced)).with(loggedIn()))
                .andExpect(jsonPath("$.data.experience.status").value("UNKNOWN"));
        mvc.perform(get("/api/matches/" + publicIdOf(entry)).with(loggedIn()))
                .andExpect(jsonPath("$.data.experience.status").value(nullValue()));

    }

    @Test
    @DisplayName("AI 가 맞춰 보기에 답하지 못하면 아무것도 저장하지 않고 503 AI_UNAVAILABLE 이다")
    void judgementFailureIsServiceUnavailable() throws Exception {

        confirmedCard("Spring 으로 결제 API 를 만들었다");
        Long posting = posting(days(10), null);
        criterion(posting, "REQUIRED", 1, "Spring 경험", null);
        when(port.judge(any())).thenThrow(new AgentUnavailableException("실패", true, false, null));

        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match_judgement", Integer.class)).isZero();

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE job_posting SET status = 'EXPIRED' WHERE id = ?",
            "UPDATE job_posting SET status = 'REMOVED' WHERE id = ?",
            "UPDATE job_posting SET expires_at = now() - interval '1 hour' WHERE id = ?",
            "UPDATE job_posting SET content_status = 'PENDING' WHERE id = ?",
            "UPDATE job_posting SET content_status = 'METADATA_ONLY' WHERE id = ?",
            "UPDATE job_posting_criterion SET type = 'RESPONSIBILITY' WHERE job_posting_id = ?"
    })
    @DisplayName("마감·사라짐·본문 없음·필수와 우대 조건 없음처럼 목록에 없는 공고는 404 NOT_FOUND 이다")
    void postingsOutsideTheListAreNotFound(String change) throws Exception {

        confirmedCard("행동");
        Long posting = posting(days(10), null);
        criterion(posting, "REQUIRED", 1, "필수", null);
        jdbc.update(change, posting);

        mvc.perform(get("/api/matches/" + publicIdOf(posting)).with(loggedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound());

    }

    @Test
    @DisplayName("임베딩 색인이 안 된 공고도 추천에서 들어올 수 있어 상세를 볼 수 있고, 마감이 없는 상시 채용 공고도 볼 수 있다")
    void unindexedAndOpenEndedPostingsAreFound() throws Exception {

        Long unindexed = posting(days(10), null);
        criterion(unindexed, "REQUIRED", 1, "필수", null);
        jdbc.update("UPDATE job_posting SET indexed_content_hash = NULL WHERE id = ?", unindexed);
        Long openEnded = posting(null, null);
        criterion(openEnded, "REQUIRED", 1, "필수", null);

        mvc.perform(get("/api/matches/" + publicIdOf(unindexed)).with(loggedIn()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/matches/" + publicIdOf(openEnded)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source.expiresAt").value(nullValue()));

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

        Long first = confirmedCard("행동 하나");
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?",
                fixtures.insertUserRepository(myUserId, fixtures.insertRepository(10L, "grow22", "gitory")), first);
        Long second = confirmedCard("행동 둘");
        Long third = confirmedCard("행동 셋");
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?",
                fixtures.insertUserRepository(myUserId, fixtures.insertRepository(11L, "grow22", "blog")), third);
        Long posting = posting(days(30), null);
        Long required1 = criterion(posting, "REQUIRED", 1, "필수 하나", null);
        Long required2 = criterion(posting, "REQUIRED", 2, "필수 둘", null);
        criterion(posting, "REQUIRED", 3, "필수 셋", null);
        criterion(posting, "REQUIRED", 4, "어학", "LANGUAGE");
        Long preferred1 = criterion(posting, "PREFERRED", 5, "우대 하나", null);
        criterion(posting, "PREFERRED", 6, "우대 둘", null);
        criterion(posting, "RESPONSIBILITY", 7, "업무 하나", null);
        criterion(posting, "RESPONSIBILITY", 8, "업무 둘", null);
        meets(Map.of(required1, first, required2, second, preferred1, third));

        JsonNode actual = MAPPER.readTree(mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement")
                        .with(loggedIn()).with(csrf()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertSameShape(example("detail"), actual, "detail");

    }

    @Test
    @DisplayName("오류 응답이 errors.json 과 같은 모양이고 코드가 같다")
    void errorsMatchContractExample() throws Exception {

        JsonNode errors = example("errors");
        confirmedCard("행동");
        Long posting = posting(days(10), null);
        criterion(posting, "REQUIRED", 1, "필수", null);
        when(port.judge(any())).thenThrow(new AgentUnavailableException("실패", true, false, null));

        assertError(errors.get("401"), get("/api/matches/" + publicIdOf(posting)), 401);
        assertError(errors.get("404"), get("/api/matches/" + UUID.randomUUID()).with(loggedIn()), 404);
        assertError(errors.get("503"), post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn())
                .with(csrf()), 503);

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

    // AI 가 조건 id → 그 조건을 채운 카드 id 대로 답하게 하고, 나머지 조건은 아무 카드도 못 채웠다고 답한다
    private void meets(Map<Long, Long> metBy) {

        when(port.judge(any())).thenAnswer(call -> ((MatchJudgementRequest) call.getArgument(0)).conditions().stream()
                .map(condition -> new JudgedCondition(condition.conditionId(),
                        metBy.containsKey(Long.valueOf(condition.conditionId()))
                                ? List.of(new ConditionSupport(String.valueOf(metBy.get(Long.valueOf(condition.conditionId()))), "이 카드가 채웠다"))
                                : List.of()))
                .toList());

    }

    private Long confirmedCard(String action) {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", action, "USER_STATED", null);
        fixtures.confirmCard(card);
        return card;

    }

    private String publicIdOfCard(Long card) {

        return jdbc.queryForObject("SELECT public_id::text FROM card WHERE id = ?", String.class, card);

    }

    private String publicIdOf(Long posting) {

        return jdbc.queryForObject("SELECT public_id::text FROM job_posting WHERE id = ?", String.class, posting);

    }

    private Long posting(Instant expiresAt, Integer minYears) {

        postingSeq++;
        return jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, category,
                    experience_level, experience_min_years, experience_quote, status, content_status, content_hash,
                    indexed_content_hash, expires_at, first_seen_at, last_seen_at)
                VALUES (?, ?, '나래랩스', '서버 개발자', ?, 'BACKEND', ?, ?, '신입 지원 가능', 'ACTIVE', 'READY', ?, ?, ?,
                        now(), now())
                RETURNING id
                """, Long.class, "company" + postingSeq, "posting-" + postingSeq,
                "https://careers.example.com/jobs/" + postingSeq, minYears == null ? "ENTRY" : "EXPERIENCED", minYears,
                HASH, HASH, expiresAt == null ? null : Timestamp.from(expiresAt));

    }

    private Long criterion(Long posting, String type, int seq, String text, String generalType) {

        return jdbc.queryForObject("""
                INSERT INTO job_posting_criterion (job_posting_id, type, seq, text, quote, general_type)
                VALUES (?, ?, ?, ?, '원문 인용', ?)
                RETURNING id
                """, Long.class, posting, type, seq, text, generalType);

    }

    private static Instant days(int days) {

        return Instant.now().plus(days, ChronoUnit.DAYS);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
