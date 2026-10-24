package com.gitory.backend.common.infra.ratelimit;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.InterviewFixtures.TurnRow;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.cards.ai-draft.enabled=true",
        "gitory.rate-limit.enabled=true",
        "gitory.rate-limit.interview-ask.per-day=1",
        "gitory.rate-limit.interview-answer.per-day=1",
        "gitory.rate-limit.card-draft.per-day=2"
})
@AutoConfigureMockMvc
@Testcontainers
class RateLimitApiTest {

    private static final FakeInterviewAi AI = FakeInterviewAi.start();
    private static final UUID FIRST_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHERS_CARD = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MY_REPO = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {

        registry.add("gitory.ai.base-url", AI::baseUrl);

    }

    @AfterAll
    static void stopAi() {

        AI.close();

    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    private TestFixtures fixtures;
    private InterviewFixtures interviews;
    private CandidateCardFixtures candidates;
    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);
        candidates = new CandidateCardFixtures(jdbc);
        fixtures.clear();
        AI.reset();
        redis.delete(redis.keys("rate:*"));

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("되묻기 질문이 한도를 넘으면 429 RATE_LIMITED 와 Retry-After 를 주고, 질문을 남기지 않으며 AI 를 부르지 않는다")
    void askOverLimitIsRejectedWithoutSavingOrCallingAi() throws Exception {

        fixtures.insertCard(FIRST_CARD, myUserId);
        Long second = fixtures.insertCard(SECOND_CARD, myUserId);
        ask(FIRST_CARD, myUserId).andExpect(status().isOk());

        MvcResult rejected = ask(SECOND_CARD, myUserId)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.message").value(startsWith("요청이 많아요. ")))
                .andReturn();

        long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isPositive();
        Number detailed = JsonPath.read(rejected.getResponse().getContentAsString(), "$.error.details.retryAfterSec");
        assertThat(detailed.longValue()).isEqualTo(retryAfter);
        assertThat(interviews.turnsOf(second)).isEmpty();
        assertThat(AI.requests()).hasSize(1);

    }

    @Test
    @DisplayName("남아 있던 질문을 돌려줄 때는 AI 를 부르지 않으므로 한도에 닿아도 그 질문을 200 으로 돌려준다")
    void openTurnIsReturnedEvenAtLimit() throws Exception {

        fixtures.insertCard(FIRST_CARD, myUserId);
        ask(FIRST_CARD, myUserId).andExpect(status().isOk());

        ask(FIRST_CARD, myUserId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(1));

        assertThat(AI.requests()).hasSize(1);

    }

    @Test
    @DisplayName("한도에 닿았어도 소유권 확인이 먼저라 남의 카드에 대한 요청은 429 가 아니라 404 다")
    void ownershipIsCheckedBeforeLimit() throws Exception {

        fixtures.insertCard(FIRST_CARD, myUserId);
        fixtures.insertCard(OTHERS_CARD, othersUserId);
        ask(FIRST_CARD, myUserId).andExpect(status().isOk());

        ask(OTHERS_CARD, myUserId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

    }

    @Test
    @DisplayName("답 저장은 글이든 넘기기든 AI 를 부르지 않아 답 한도를 쓰지 않으므로, 한도가 하루 1번이어도 둘 다 저장된다")
    void answersNeitherCallAiNorUseTheLimit() throws Exception {

        Long first = fixtures.insertCard(FIRST_CARD, myUserId);
        Long second = fixtures.insertCard(SECOND_CARD, myUserId);
        interviews.insertAsked(first, 1, "A", "EVIDENCE_GAP", "USER", "무엇을 했나요?");
        interviews.insertAsked(second, 1, "A", "EVIDENCE_GAP", "USER", "무엇을 했나요?");

        answer(FIRST_CARD).andExpect(status().isOk());
        mvc.perform(post("/api/cards/{id}/interview/{turnNo}/answer", SECOND_CARD, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SKIP\"}")
                        .with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk());

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(first)).extracting(TurnRow::outcome).containsExactly("ANSWERED");
        assertThat(interviews.turnsOf(second)).extracting(TurnRow::outcome).containsExactly("SKIPPED");

    }

    @Test
    @DisplayName("카드 초안이 켜져 있으면 카드 수만큼 세고, 남은 횟수를 넘는 요청은 카드·Job·후보 상태 어느 것도 바꾸지 않고 통째로 거절한다")
    void cardCreationCountsCandidatesAndRejectsWholeRequest() throws Exception {

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        Long a = candidates.insertNewCandidate(myRepoId, "가");
        Long b = candidates.insertNewCandidate(myRepoId, "나");
        Long c = candidates.insertNewCandidate(myRepoId, "다");

        createCards(List.of(a, b, c))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class)).isZero();
        assertThat(List.of(a, b, c)).allSatisfy(id -> assertThat(candidates.statusOf(id)).isEqualTo("NEW"));

        createCards(List.of(a, b)).andExpect(status().isOk());
        createCards(List.of(c)).andExpect(status().isTooManyRequests());

    }

    private ResultActions ask(UUID cardId, Long userId) throws Exception {

        return mvc.perform(post("/api/cards/{id}/interview", cardId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"T\"}")
                .with(loggedInAs(userId)).with(csrf()));

    }

    private ResultActions answer(UUID cardId) throws Exception {

        return mvc.perform(post("/api/cards/{id}/interview/{turnNo}/answer", cardId, 1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"고쳤다\",\"source\":\"USER_STATED\"}")
                .with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions createCards(List<Long> candidateIds) throws Exception {

        String ids = String.join(",", candidateIds.stream()
                .map(id -> "\"" + candidates.publicIdOf(id) + "\"").toList());

        return mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateIds\":[" + ids + "]}")
                .with(loggedInAs(myUserId)).with(csrf()));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
