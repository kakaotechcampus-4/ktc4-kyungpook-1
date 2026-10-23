package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.InterviewFixtures.TurnRow;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI 응답을 기다리는 사이에 다른 요청이 끼어드는 경우를, 가짜 AI 가 응답을 붙들어 두는 방식으로 정해진 순서대로 재현한다 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.interview.max-turns=2"
})
@AutoConfigureMockMvc
@Testcontainers
class InterviewAskConcurrencyTest {

    private static final FakeInterviewAi AI = FakeInterviewAi.start();
    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String WAIT_FOR_LOCK = "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

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
    PlatformTransactionManager transactionManager;

    private TestFixtures fixtures;
    private InterviewFixtures interviews;
    private ExecutorService pool;
    private Long myUserId;
    private Long cardId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);
        fixtures.clear();
        AI.reset();
        pool = Executors.newFixedThreadPool(4);

        myUserId = fixtures.insertUser(1L, "grow22");
        cardId = fixtures.insertCard(MY_CARD, myUserId);

    }

    @AfterEach
    void stopPool() {

        pool.shutdownNow();

    }

    @Test
    @DisplayName("같은 카드에 질문 요청 둘이 동시에 와도 질문은 하나만 만들어지고, 둘 다 먼저 저장된 그 질문을 받는다")
    void simultaneousRequestsCreateOneTurn() throws Exception {

        CyclicBarrier bothAsked = new CyclicBarrier(2);
        AtomicInteger replies = new AtomicInteger();
        // 두 요청이 모두 AI 에 닿을 때까지 응답을 붙들어, 둘 다 질문이 없는 것을 보고 온 상태로 저장에 들어가게 한다
        AI.beforeReply(request -> bothAsked.await(10, TimeUnit.SECONDS));
        AI.replyWith(request -> FakeInterviewAi.askAgain(cardId, "T", "EVIDENCE_GAP", "AUTO",
                "AI 가 " + replies.incrementAndGet() + "번째로 만든 질문"));

        Future<String> first = pool.submit(() -> askBody("T"));
        Future<String> second = pool.submit(() -> askBody("T"));
        String firstBody = first.get(20, TimeUnit.SECONDS);
        String secondBody = second.get(20, TimeUnit.SECONDS);

        assertThat(AI.requests()).hasSize(2);
        assertThat(interviews.turnsOf(cardId)).hasSize(1);
        String stored = interviews.turnsOf(cardId).get(0).questionText();
        assertThat(JsonPath.<String>read(firstBody, "$.data.question")).isEqualTo(stored);
        assertThat(JsonPath.<String>read(secondBody, "$.data.question")).isEqualTo(stored);
        assertThat(JsonPath.<Integer>read(firstBody, "$.data.turnNo")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(secondBody, "$.data.turnNo")).isEqualTo(1);

    }

    @Test
    @DisplayName("AI 응답을 기다리는 동안에는 열려 있는 트랜잭션도 카드 행 잠금도 없다")
    void holdsNoTransactionOrLockWhileWaitingForAi() throws Exception {

        AtomicReference<Integer> openTransactions = new AtomicReference<>();
        AtomicReference<String> lockFailure = new AtomicReference<>();
        AI.beforeReply(request -> {
            openTransactions.set(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND state = 'idle in transaction'",
                    Integer.class));
            try {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE NOWAIT", Long.class, cardId);
            } catch (DataAccessException failure) {
                lockFailure.set(failure.getMessage());
            }
        });

        ask("T").andExpect(status().isOk());

        assertThat(AI.requests()).hasSize(1);
        assertThat(openTransactions.get()).isZero();
        assertThat(lockFailure.get()).isNull();

    }

    @Test
    @DisplayName("저장은 AI 를 다녀온 뒤 카드 행 잠금을 얻어야 해서, 다른 트랜잭션이 카드 행을 쥐고 있으면 놓을 때까지 기다린 다음 질문을 저장한다")
    void savingWaitsForTheCardRowLock() throws Exception {

        CountDownLatch aiAsked = new CountDownLatch(1);
        CountDownLatch aiMayReply = new CountDownLatch(1);
        AI.beforeReply(request -> {
            aiAsked.countDown();
            aiMayReply.await(10, TimeUnit.SECONDS);
        });
        Future<String> asking = pool.submit(() -> askBody("T"));
        assertThat(aiAsked.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch unlock = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
            locked.countDown();
            awaitQuietly(unlock);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        aiMayReply.countDown();
        awaitLockWaiters();
        assertThat(asking.isDone()).isFalse();
        assertThat(interviews.turnsOf(cardId)).isEmpty();

        unlock.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(JsonPath.<Integer>read(asking.get(20, TimeUnit.SECONDS), "$.data.turnNo")).isEqualTo(1);
        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 카드가 확정되면 질문을 만들지 않고 409 CARD_CONFIRMED 이다")
    void rechecksConfirmedStateAfterAi() throws Exception {

        AI.beforeReply(request -> fixtures.confirmCard(cardId));

        ask("T")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CARD_CONFIRMED"));

        assertThat(AI.requests()).hasSize(1);
        assertThat(interviews.turnsOf(cardId)).isEmpty();

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 질문 수가 상한에 닿으면 질문을 더 만들지 않고 409 INTERVIEW_CAP 이다")
    void rechecksCapAfterAi() throws Exception {

        AI.beforeReply(request -> {
            interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");
            interviews.insertAnswered(cardId, 2, "A", "둘째 답", "TYPED");
        });

        ask("T")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INTERVIEW_CAP"));

        assertThat(AI.requests().get(0).path("existing_turn_count").intValue()).isZero();
        assertThat(interviews.turnsOf(cardId)).hasSize(2);

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 다른 요청이 질문을 만들었으면 새 질문을 만들지 않고 그 질문을 돌려준다")
    void rechecksOpenTurnAfterAi() throws Exception {

        AI.beforeReply(request ->
                interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "다른 요청이 만든 질문"));

        ask("T")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(1))
                .andExpect(jsonPath("$.data.field").value("S"))
                .andExpect(jsonPath("$.data.question").value("다른 요청이 만든 질문"));

        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    @Test
    @DisplayName("새 질문 번호는 AI 를 기다린 뒤 저장할 때 읽은 최댓값 + 1 이다")
    void numbersTheQuestionWhenSaving() throws Exception {

        AI.beforeReply(request -> interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED"));

        ask("T")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(2));

        assertThat(AI.requests().get(0).path("existing_turn_count").intValue()).isZero();
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::seq).containsExactly(1, 2);

    }

    private ResultActions ask(String field) throws Exception {

        return mvc.perform(post("/api/cards/{id}/interview", MY_CARD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"" + field + "\"}")
                .with(loggedInAs(myUserId))
                .with(csrf()));

    }

    private String askBody(String field) throws Exception {

        return ask(field).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

    }

    private void awaitLockWaiters() throws InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(WAIT_FOR_LOCK, Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }

        throw new AssertionError("잠금을 기다리는 요청이 생기지 않았다");

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
}
