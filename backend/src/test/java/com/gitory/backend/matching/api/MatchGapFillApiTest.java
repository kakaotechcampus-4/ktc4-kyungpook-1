package com.gitory.backend.matching.api;

import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.GapFillAction;
import com.gitory.backend.matching.domain.GapFillCondition;
import com.gitory.backend.matching.domain.GapFillDecision;
import com.gitory.backend.matching.domain.GapFillPort;
import com.gitory.backend.matching.domain.GapFillProposal;
import com.gitory.backend.matching.domain.GapFillRequest;
import com.gitory.backend.matching.domain.GapFillResult;
import com.gitory.backend.matching.domain.GapFillStep;
import com.gitory.backend.matching.domain.GapFillStepKind;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
class MatchGapFillApiTest {

    private static final String HASH = "a".repeat(64);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    MatchJudgementPort judgePort;

    @MockitoBean
    GapFillPort gapFillPort;

    @MockitoBean
    UserRateLimiter rateLimiter;

    @MockitoBean
    GithubCollectionAccessPort githubAccess;

    private TestFixtures fixtures;
    private CandidateBoardFixtures candidates;
    private Long myUserId;
    private Long posting;
    private Long gap;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        candidates = new CandidateBoardFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        myUserId = fixtures.insertUser(1L, "grow22");
        posting = jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, category,
                    status, content_status, content_hash, indexed_content_hash, expires_at, first_seen_at, last_seen_at)
                VALUES ('narae', '1', '나래랩스', '서버 개발자', 'https://careers.example.com/1', 'BACKEND', 'ACTIVE',
                        'READY', ?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, HASH, HASH, Timestamp.from(Instant.now().plus(10, ChronoUnit.DAYS)));
        gap = jdbc.queryForObject("""
                INSERT INTO job_posting_criterion (job_posting_id, type, seq, text, quote)
                VALUES (?, 'REQUIRED', 1, 'Kafka 로 메시지를 처리해 본 경험', '원문 인용') RETURNING id
                """, Long.class, posting);
        when(judgePort.judge(any())).thenAnswer(call -> ((MatchJudgementRequest) call.getArgument(0)).conditions()
                .stream()
                .map(condition -> new JudgedCondition(condition.conditionId(), List.of()))
                .toList());
        when(gapFillPort.fill(any(), any(), any())).thenReturn(List.of());

    }

    @Test
    @DisplayName("아직 맞춰 보지 않은 조건이 있으면 409 INVALID_STATE 이고 AI 를 부르지 않는다")
    void needsJudgementFirst() throws Exception {

        confirmedCard();

        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        verify(gapFillPort, never()).fill(any(), any(), any());

    }

    @Test
    @DisplayName("AI 에는 새 후보만 그 레포 이름·커밋 메시지 첫 줄과 함께, 작성 중 카드만 draftCards 로, 레포는 분석 여부와 함께, 로그인과 같이 모두 공개 id 로 보낸다")
    void sendsMaterialsWithPublicIds() throws Exception {

        Long confirmed = confirmedCard();
        Long draft = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(draft, 1, "A", "캐시로 응답을 줄였다", "USER_STATED", null);
        Long payRepo = fixtures.insertRepository(10L, "grow22", "pay");
        Long pay = fixtures.insertUserRepository(myUserId, payRepo);
        jdbc.update("UPDATE user_repository SET last_analyzed_at = now() WHERE id = ?", pay);
        Long shop = fixtures.insertUserRepository(myUserId, fixtures.insertRepository(11L, "grow22", "shop"));
        Long fresh = candidates.insertCandidate(pay, "COMMIT_CLUSTER", null, "NEW", "주문 큐", "0.9");
        Long kept = fixtures.insertCommit(payRepo, "abc1234");
        jdbc.update("UPDATE git_commit SET message = ? WHERE id = ?", "feat: 주문 큐 붙이기\n\n본문", kept);
        candidates.insertCandidateCommit(fresh, kept, true, "src/OrderQueue.java");
        candidates.insertCandidateCommit(fresh, fixtures.insertCommit(payRepo, "def5678"), false, "src/Old.java");
        candidates.insertCandidate(pay, "COMMIT_CLUSTER", null, "USED", "쓴 후보", "0.8");
        candidates.insertCandidate(pay, "COMMIT_CLUSTER", null, "EXCLUDED", "뺀 후보", "0.7");
        judge();
        CountDownLatch release = new CountDownLatch(1);
        when(gapFillPort.fill(any(), any(), any())).thenAnswer(call -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gapFill.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.gapFill.pollAfterMs").value(1500));

        ArgumentCaptor<GapFillRequest> sent = ArgumentCaptor.forClass(GapFillRequest.class);
        verify(gapFillPort, timeout(5_000)).fill(eq(myUserId), sent.capture(), any());
        release.countDown();
        GapFillRequest request = sent.getValue();
        assertThat(request.conditions()).containsExactly(new GapFillRequest.Condition(publicIdOf("job_posting_criterion", gap),
                CriterionType.REQUIRED, "Kafka 로 메시지를 처리해 본 경험"));
        assertThat(request.cards()).extracting(GapFillRequest.Card::cardId).containsExactly(publicIdOf("card", confirmed));
        assertThat(request.draftCards()).extracting(GapFillRequest.Card::cardId).containsExactly(publicIdOf("card", draft));
        assertThat(request.draftCards().getFirst().action()).isEqualTo("캐시로 응답을 줄였다");
        assertThat(request.candidates()).containsExactly(new GapFillRequest.Candidate(candidates.publicIdOf(fresh),
                "grow22/pay", "주문 큐", "추천 이유", List.of("feat: 주문 큐 붙이기")));
        assertThat(request.repos()).containsExactly(new GapFillRequest.Repo(publicIdOf("user_repository", pay), "grow22/pay", true),
                new GapFillRequest.Repo(publicIdOf("user_repository", shop), "grow22/shop", false));
        assertThat(request.githubLogin()).isEqualTo("grow22");
        assertThat(request.companyName()).isEqualTo("나래랩스");

    }

    @Test
    @DisplayName("채우기가 끝나면 상세 조회의 gapFill 은 저장한 결론으로 DONE 이고, 제안은 종류에 맞는 id 칸과 후보가 나온 레포 id·후보 제목·레포 이름을 담으며 pollAfterMs 는 null 이다")
    void showsStoredResultAfterFinishing() throws Exception {

        confirmedCard();
        Long pay = fixtures.insertUserRepository(myUserId, fixtures.insertRepository(10L, "grow22", "pay"));
        jdbc.update("UPDATE user_repository SET last_analyzed_at = now() WHERE id = ?", pay);
        Long fresh = candidates.insertCandidate(pay, "COMMIT_CLUSTER", null, "NEW", "주문 큐", "0.9");
        UUID condition = publicIdOf("job_posting_criterion", gap);
        UUID candidate = candidates.publicIdOf(fresh);
        when(gapFillPort.fill(any(), any(), any())).thenReturn(List.of(new GapFillCondition(condition,
                GapFillDecision.SEARCH, "주문 처리와 관련 있음", false,
                List.of(new GapFillStep(GapFillStepKind.CANDIDATES_CHECKED, 1, null, null)),
                new GapFillProposal(GapFillAction.CREATE_CARD, candidate, null, null, null, "큐로 주문을 처리했다"),
                GapFillResult.PROPOSED)));
        judge();

        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf())).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).until(() -> "DONE".equals(jdbc.queryForObject(
                "SELECT status FROM match_gap_fill WHERE user_id = ?", String.class, myUserId)));

        mvc.perform(get("/api/matches/" + publicIdOf("job_posting", posting)).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gapFill.state").value("DONE"))
                .andExpect(jsonPath("$.data.gapFill.pollAfterMs").value(nullValue()))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].conditionId").value(condition.toString()))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].decision").value("SEARCH"))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].steps[0].kind").value("CANDIDATES_CHECKED"))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].steps[0].count").value(1))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].proposal.action").value("CREATE_CARD"))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].proposal.candidateId").value(candidate.toString()))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].proposal.cardId").value(nullValue()))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].proposal.repoId").value(publicIdOf("user_repository", pay).toString()))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].proposal.title").value("주문 큐"))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].proposal.repoName").value("grow22/pay"))
                .andExpect(jsonPath("$.data.gapFill.conditions[0].result").value("PROPOSED"));

    }

    @Test
    @DisplayName("호출 한도를 넘으면 429 RATE_LIMITED 이고 채우기를 시작하지 않는다")
    void rateLimitedStartIsTooManyRequests() throws Exception {

        confirmedCard();
        judge();
        doThrow(new RateLimitExceededException(30)).when(rateLimiter)
                .acquire(RateLimitedAction.MATCH_GAP_FILL, myUserId, 1);

        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match_gap_fill", Integer.class)).isZero();
        verify(gapFillPort, never()).fill(any(), any(), any());

    }

    @Test
    @DisplayName("본문에 다시 찾을 조건을 주면 끝난 결과에서 '찾지 않음' 으로 멈춘 그 조건만 AI 에 첫 판단과 상관없이 찾게 보낸다")
    void forcesOnlyTheChosenSkippedCondition() throws Exception {

        confirmedCard();
        judge();
        UUID condition = publicIdOf("job_posting_criterion", gap);
        finishedWithSkipped(condition);

        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionIds\": [\"" + condition + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gapFill.state").value("RUNNING"));

        ArgumentCaptor<GapFillRequest> sent = ArgumentCaptor.forClass(GapFillRequest.class);
        verify(gapFillPort, timeout(5_000)).fill(eq(myUserId), sent.capture(), any());
        assertThat(sent.getValue().conditions()).extracting(GapFillRequest.Condition::conditionId)
                .containsExactly(condition);
        assertThat(sent.getValue().forceConditionIds()).containsExactly(condition);

    }

    @Test
    @DisplayName("끝난 결과가 없으면 409 INVALID_STATE, 멈추지 않은 조건을 다시 찾게 하면 400 INVALID_REQUEST 이고 AI 를 부르지 않는다")
    void rejectsForcingWithoutASkippedFinishedResult() throws Exception {

        confirmedCard();
        judge();
        UUID condition = publicIdOf("job_posting_criterion", gap);

        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionIds\": [\"" + condition + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        finishedWithSkipped(condition);
        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionIds\": [\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verify(gapFillPort, never()).fill(any(), any(), any());

    }

    @Test
    @DisplayName("새로 찾아야 하는데 쓸 수 있는 GitHub 연결이 없으면 403 GITHUB_UNAUTHORIZED 이고 채우기를 시작하지 않는다")
    void needsGithubToStartSearching() throws Exception {

        confirmedCard();
        judge();
        doThrow(new GithubNotConnectedException()).when(githubAccess).requireUsableConnection(myUserId);

        mvc.perform(post(gapFillPath()).with(loggedIn()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("GITHUB_UNAUTHORIZED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match_gap_fill", Integer.class)).isZero();
        verify(gapFillPort, never()).fill(any(), any(), any());

    }

    private void finishedWithSkipped(UUID condition) {

        jdbc.update("""
                INSERT INTO match_gap_fill (user_id, job_posting_id, status, conditions, fingerprint, requested_at,
                                            finished_at)
                VALUES (?, ?, 'DONE', CAST(? AS jsonb), 'f', now(), now())
                """, myUserId, posting, """
                [{"conditionId": "%s", "decision": "SKIP", "decisionReason": "분야가 다르다", "forced": false,
                  "steps": [], "proposal": null, "result": "SKIPPED"}]""".formatted(condition));

    }

    private void judge() throws Exception {

        mvc.perform(post("/api/matches/" + publicIdOf("job_posting", posting) + "/judgement").with(loggedIn())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions[0].status").value("NOT_MET"))
                .andExpect(jsonPath("$.data.gapFill").value(nullValue()));

    }

    private Long confirmedCard() {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        fixtures.insertStatement(card, 1, "A", "Spring 으로 결제 API 를 만들었다", "USER_STATED", null);
        fixtures.confirmCard(card);
        return card;

    }

    private String gapFillPath() {

        return "/api/matches/" + publicIdOf("job_posting", posting) + "/gap-fill";

    }

    private UUID publicIdOf(String table, Long id) {

        return jdbc.queryForObject("SELECT public_id FROM " + table + " WHERE id = ?", UUID.class, id);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
