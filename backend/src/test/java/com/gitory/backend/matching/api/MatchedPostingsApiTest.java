package com.gitory.backend.matching.api;

import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.JudgedCondition.ConditionSupport;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
class MatchedPostingsApiTest {

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
    private Long teammateId;
    private int postingSeq;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        myUserId = fixtures.insertUser(1L, "grow22");
        teammateId = fixtures.insertUser(2L, "teammate");
        postingSeq = 0;

    }

    @Test
    @DisplayName("내 확정 카드가 필수 조건을 채운다고 판단된 공고만 세고, 한 공고의 필수 조건 여럿을 카드 여럿이 채워도 한 번만 센다")
    void countsEachPostingOnceWhenItsRequiredConditionIsMet() throws Exception {

        Long first = confirmedCard(myUserId, "Spring 으로 결제 API 를 만들었다");
        Long second = confirmedCard(myUserId, "Redis 로 캐시를 붙였다");
        Long both = posting(days(10));
        Long spring = criterion(both, "REQUIRED", 1);
        Long redis = criterion(both, "REQUIRED", 2);
        Long missed = posting(days(10));
        criterion(missed, "REQUIRED", 1);
        meets(Map.of(spring, first, redis, second));
        judge(both, myUserId);
        judge(missed, myUserId);

        mvc.perform(get("/api/matches/summary").with(loggedIn(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedPostings").value(1));

    }

    @Test
    @DisplayName("카드 글이 바뀌면 바뀌기 전의 판단은 세지 않는다")
    void ignoresJudgementsOfOldCardText() throws Exception {

        Long card = confirmedCard(myUserId, "Spring 으로 결제 API 를 만들었다");
        Long posting = posting(days(10));
        Long spring = criterion(posting, "REQUIRED", 1);
        meets(Map.of(spring, card));
        judge(posting, myUserId);
        jdbc.update("UPDATE card_statement SET body = '결제 API 를 다시 짰다' WHERE card_id = ?", card);

        mvc.perform(get("/api/matches/summary").with(loggedIn(myUserId)))
                .andExpect(jsonPath("$.data.matchedPostings").value(0));

    }

    @Test
    @DisplayName("우대 조건만 채운 공고와 마감된 공고는 세지 않는다")
    void ignoresPreferredOnlyAndExpiredPostings() throws Exception {

        Long card = confirmedCard(myUserId, "Spring 으로 결제 API 를 만들었다");
        Long preferredOnly = posting(days(10));
        criterion(preferredOnly, "REQUIRED", 1);
        Long preferred = criterion(preferredOnly, "PREFERRED", 2);
        Long closing = posting(days(10));
        Long required = criterion(closing, "REQUIRED", 1);
        meets(Map.of(preferred, card, required, card));
        judge(preferredOnly, myUserId);
        judge(closing, myUserId);
        jdbc.update("UPDATE job_posting SET expires_at = ? WHERE id = ?", Timestamp.from(days(-1)), closing);

        mvc.perform(get("/api/matches/summary").with(loggedIn(myUserId)))
                .andExpect(jsonPath("$.data.matchedPostings").value(0));

    }

    @Test
    @DisplayName("확정 카드가 없으면 0 이고 AI 를 부르지 않는다")
    void isZeroWithoutConfirmedCards() throws Exception {

        Long posting = posting(days(10));
        criterion(posting, "REQUIRED", 1);

        mvc.perform(get("/api/matches/summary").with(loggedIn(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedPostings").value(0));
        verify(port, never()).judge(any());

    }

    @Test
    @DisplayName("다른 사용자의 카드가 채운 판단은 내 수에 세지 않는다")
    void ignoresOtherUsersJudgements() throws Exception {

        Long theirs = confirmedCard(teammateId, "Spring 으로 결제 API 를 만들었다");
        confirmedCard(myUserId, "문서를 정리했다");
        Long posting = posting(days(10));
        Long spring = criterion(posting, "REQUIRED", 1);
        meets(Map.of(spring, theirs));
        judge(posting, teammateId);

        mvc.perform(get("/api/matches/summary").with(loggedIn(myUserId)))
                .andExpect(jsonPath("$.data.matchedPostings").value(0));
        mvc.perform(get("/api/matches/summary").with(loggedIn(teammateId)))
                .andExpect(jsonPath("$.data.matchedPostings").value(1));

    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void requiresLogin() throws Exception {

        mvc.perform(get("/api/matches/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    private void judge(Long posting, Long userId) throws Exception {

        mvc.perform(post("/api/matches/" + publicIdOf(posting) + "/judgement").with(loggedIn(userId)).with(csrf()))
                .andExpect(status().isOk());

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

    private Long confirmedCard(Long userId, String action) {

        Long card = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertStatement(card, 1, "A", action, "USER_STATED", null);
        fixtures.confirmCard(card);
        return card;

    }

    private String publicIdOf(Long posting) {

        return jdbc.queryForObject("SELECT public_id::text FROM job_posting WHERE id = ?", String.class, posting);

    }

    private Long posting(Instant expiresAt) {

        postingSeq++;
        return jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, category,
                    experience_level, experience_min_years, experience_quote, status, content_status, content_hash,
                    indexed_content_hash, expires_at, first_seen_at, last_seen_at)
                VALUES (?, ?, '나래랩스', '서버 개발자', ?, 'BACKEND', 'ENTRY', NULL, '신입 지원 가능', 'ACTIVE', 'READY', ?, ?, ?,
                        now(), now())
                RETURNING id
                """, Long.class, "company" + postingSeq, "posting-" + postingSeq,
                "https://careers.example.com/jobs/" + postingSeq, HASH, HASH, Timestamp.from(expiresAt));

    }

    private Long criterion(Long posting, String type, int seq) {

        return jdbc.queryForObject("""
                INSERT INTO job_posting_criterion (job_posting_id, type, seq, text, quote)
                VALUES (?, ?, ?, '조건', '원문 인용')
                RETURNING id
                """, Long.class, posting, type, seq);

    }

    private static Instant days(int days) {

        return Instant.now().plus(days, ChronoUnit.DAYS);

    }

    private RequestPostProcessor loggedIn(Long userId) {

        LoginUser principal = new LoginUser(userId, userId.equals(myUserId) ? "grow22" : "teammate", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
