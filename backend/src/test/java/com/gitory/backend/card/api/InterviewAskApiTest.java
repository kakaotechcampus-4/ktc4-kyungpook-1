package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.InterviewFixtures.TurnRow;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
        "gitory.consent.token-salt=5c0744940b5c369b",
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.path=/api/docs"
})
@AutoConfigureMockMvc
@Testcontainers
class InterviewAskApiTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final FakeInterviewAi AI = FakeInterviewAi.start();
    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String ASK_PATH = "/api/cards/{id}/interview";

    // FE schemas.ts 의 InterviewTurn 이 요구하는 키로, 값이 null 이어도 키는 빠지면 안 된다
    private static final String[] TURN_KEYS = {"turnNo", "field", "askedBy", "sourceType", "questionType", "found",
            "missing", "question", "options", "answer", "remaining", "maxTurns"};

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

    private TestFixtures fixtures;
    private InterviewFixtures interviews;
    private Long myUserId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);
        fixtures.clear();
        AI.reset();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("로그인하지 않고 요청하면 401 UNAUTHENTICATED 이고 AI 를 부르지 않는다")
    void requiresLogin() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        mvc.perform(post(ASK_PATH, MY_CARD).contentType(MediaType.APPLICATION_JSON).content(body("T")).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        assertThat(AI.requests()).isEmpty();

    }

    @Test
    @DisplayName("로그인했어도 CSRF 토큰이 없으면 403 FORBIDDEN 이고 AI 를 부르지 않으며 질문도 만들지 않는다")
    void requiresCsrfToken() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);

        mvc.perform(post(ASK_PATH, MY_CARD).contentType(MediaType.APPLICATION_JSON).content(body("T"))
                        .with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 면 404 NOT_FOUND 이고 거절 기록을 남기지 않으며 AI 를 부르지 않는다")
    void unknownCardIsNotFoundWithoutRecord(String id) throws Exception {

        ask(myUserId, id, "T")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();
        assertThat(AI.requests()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"T", "X"})
    @DisplayName("남의 카드는 field 가 잘못돼도 없는 카드와 똑같이 404 이고 EDIT_CARD 거절 기록이 그 카드 내부 id 로 하나 남으며 질문도 AI 도 없다")
    void othersCardIsNotFoundAndRecorded(String field) throws Exception {

        expectDeniedAsNotFound(othersCard(), field);

    }

    @Test
    @DisplayName("남의 카드에 답하지 않은 질문이 있어도 그 질문을 돌려주지 않고 404 이며 거절 기록이 하나 남고 질문은 그대로이고 AI 를 부르지 않는다")
    void othersCardWithOpenTurnIsStillNotFound() throws Exception {

        Long othersCardId = othersCard();
        interviews.insertAsked(othersCardId, 1, "T", "EVIDENCE_GAP", "USER", "남의 열린 질문");

        expectDeniedAsNotFound(othersCardId, "T");

    }

    @Test
    @DisplayName("남의 카드가 확정돼 있어도 409 CARD_CONFIRMED 가 아니라 404 이며 거절 기록이 하나 남고 질문은 그대로이고 AI 를 부르지 않는다")
    void othersConfirmedCardIsStillNotFound() throws Exception {

        Long othersCardId = othersCard();
        fixtures.insertCardVersion(othersCardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(othersCardId);

        expectDeniedAsNotFound(othersCardId, "T");

    }

    @Test
    @DisplayName("남의 카드가 질문 상한에 닿았어도 409 INTERVIEW_CAP 이 아니라 404 이며 거절 기록이 하나 남고 질문은 그대로이고 AI 를 부르지 않는다")
    void othersCardAtTheCapIsStillNotFound() throws Exception {

        Long othersCardId = othersCard();
        interviews.insertAnswered(othersCardId, 1, "S", "남의 첫 답", "TYPED");
        interviews.insertAnswered(othersCardId, 2, "T", "남의 둘째 답", "TYPED");

        expectDeniedAsNotFound(othersCardId, "T");

    }

    @Test
    @DisplayName("질문을 받는 데 성공해도 감사 기록은 하나도 남지 않는다")
    void successLeavesNoAuditRow() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        ask(myUserId, MY_CARD.toString(), "T").andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("확정된 카드는 409 CARD_CONFIRMED 이고 AI 를 부르지 않으며 질문도 감사 기록도 남지 않는다")
    void confirmedCardIsConflict() throws Exception {

        Long cardId = confirmedCard();

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CARD_CONFIRMED"));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).isEmpty();
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("확정된 카드는 질문을 모두 답했어도 상한 409 INTERVIEW_CAP 이 아니라 409 CARD_CONFIRMED 이고 AI 를 부르지 않는다")
    void confirmedCardWithAllTurnsAnsweredIsStillConfirmedConflict() throws Exception {

        Long cardId = confirmedCard();
        interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");
        interviews.insertAnswered(cardId, 2, "T", "둘째 답", "TYPED");

        ask(myUserId, MY_CARD.toString(), "A")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CARD_CONFIRMED"));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).hasSize(2);

    }

    @Test
    @DisplayName("확정된 카드는 field 가 잘못돼도 400 이 아니라 409 CARD_CONFIRMED 이다")
    void confirmedCardWithBadFieldIsStillConflict() throws Exception {

        confirmedCard();

        ask(myUserId, MY_CARD.toString(), "X")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CARD_CONFIRMED"));

    }

    @Test
    @DisplayName("확정된 카드에 답하지 않은 질문이 남아 있어도 그 질문이 아니라 409 CARD_CONFIRMED 이다")
    void confirmedCardWithOpenTurnIsStillConflict() throws Exception {

        Long cardId = confirmedCard();
        fixtures.insertInterviewTurn(cardId, 1, "S");

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CARD_CONFIRMED"));

    }

    @Test
    @DisplayName("답하지 않은 질문이 있으면 같은 칸을 요청해도 새로 만들지 않고 그 질문을 200 으로 돌려주며 AI 를 부르지 않는다")
    void returnsOpenTurnWithoutCallingAi() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "T", "FOLLOWUP", "AUTO", "열려 있는 질문");

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(1))
                .andExpect(jsonPath("$.data.field").value("T"))
                .andExpect(jsonPath("$.data.askedBy").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.data.question").value("열려 있는 질문"));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    @Test
    @DisplayName("답하지 않은 질문이 다른 칸의 것이어도 요청한 칸과 상관없이 그 질문을 돌려준다")
    void returnsOpenTurnOfAnotherSlot() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "상황 질문");

        ask(myUserId, MY_CARD.toString(), "R")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(1))
                .andExpect(jsonPath("$.data.field").value("S"))
                .andExpect(jsonPath("$.data.question").value("상황 질문"));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    @ParameterizedTest
    @ValueSource(strings = {"X", "", "s"})
    @DisplayName("답하지 않은 질문이 있으면 field 가 잘못돼도 400 이 아니라 그 질문을 200 으로 돌려준다")
    void returnsOpenTurnEvenWithBadField(String field) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "A", "EVIDENCE_GAP", "USER", "행동 질문");

        ask(myUserId, MY_CARD.toString(), field)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("A"))
                .andExpect(jsonPath("$.data.question").value("행동 질문"));

    }

    @Test
    @DisplayName("만든 질문 수가 상한이어도 그중 답하지 않은 질문이 있으면 409 가 아니라 그 질문을 돌려준다")
    void openTurnComesBeforeTheCap() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");
        interviews.insertAsked(cardId, 2, "T", "EVIDENCE_GAP", "USER", "둘째 질문");

        ask(myUserId, MY_CARD.toString(), "A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(2))
                .andExpect(jsonPath("$.data.question").value("둘째 질문"));

        assertThat(AI.requests()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"LATER", "SKIPPED", "INSUFFICIENT"})
    @DisplayName("결과가 ANSWERED 가 아니면 답 글이 남아 있어도 답하지 않은 질문으로 보고 answer 가 null 인 그 질문을 돌려준다")
    void treatsOtherOutcomesAsOpen(String outcome) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "T", "EVIDENCE_GAP", "USER", "남은 질문", outcome, "남은 답 글", "TYPED");

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(1))
                .andExpect(jsonPath("$.data.answer").value(nullValue()));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    @Test
    @DisplayName("만든 질문이 상한(2)만큼이고 모두 답했으면 409 INTERVIEW_CAP 이고 AI 를 부르지 않으며 질문도 늘지 않는다")
    void capReachedIsConflict() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");
        interviews.insertAnswered(cardId, 2, "T", "둘째 답", "TYPED");

        ask(myUserId, MY_CARD.toString(), "A")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INTERVIEW_CAP"));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).hasSize(2);

    }

    @Test
    @DisplayName("상한에 닿았으면 field 가 잘못돼도 400 이 아니라 409 INTERVIEW_CAP 이다")
    void capWithBadFieldIsStillConflict() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");
        interviews.insertAnswered(cardId, 2, "T", "둘째 답", "TYPED");

        ask(myUserId, MY_CARD.toString(), "X")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INTERVIEW_CAP"));

    }

    @Test
    @DisplayName("상한보다 하나 적게 만들었으면 질문을 받고, 새 질문은 번호가 질문 번호 최댓값 + 1 이며 남은 질문은 0 이다")
    void oneBelowTheCapStillAsks() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(2))
                .andExpect(jsonPath("$.data.remaining").value(0));

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::seq).containsExactly(1, 2);

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("field 가 없거나 null 이거나 정확히 S·T·A·R 이 아니면 400 BAD_FIELD 이고 AI 를 부르지 않으며 질문도 만들지 않는다")
    void invalidFieldIsBadRequest(String content) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);

        send(myUserId, MY_CARD.toString(), content)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("BAD_FIELD"))
                .andExpect(jsonPath("$.error.message").value("field 는 S/T/A/R 중 하나여야 합니다."));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).isEmpty();

    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "not json", "[\"T\"]"})
    @DisplayName("본문을 읽을 수 없으면 전역 처리기가 400 INVALID_REQUEST 로 답한다")
    void unreadableBodyIsBadRequest(String content) throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        send(myUserId, MY_CARD.toString(), content)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(AI.requests()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"S", "T", "A", "R"})
    @DisplayName("질문을 받으면 요청한 칸의 질문을 200 으로 돌려주고, FE InterviewTurn 의 12개 키를 모두 담는다")
    void returnsTheNewQuestionWithTwelveKeys(String field) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        AI.replyWith(request -> FakeInterviewAi.askAgain(cardId, field, "RECALL_AID", "AUTO", "  앞뒤 공백이 있는 질문  "));

        String body = ask(myUserId, MY_CARD.toString(), field)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> turn = JsonPath.read(body, "$.data");
        assertThat(turn).containsOnlyKeys(TURN_KEYS)
                .containsEntry("turnNo", 1)
                .containsEntry("field", field)
                .containsEntry("askedBy", "USER_REQUEST")
                .containsEntry("sourceType", "DIRECT_CARD")
                .containsEntry("questionType", "RECALL_AID")
                .containsEntry("found", List.of())
                .containsEntry("missing", List.of())
                .containsEntry("question", "  앞뒤 공백이 있는 질문  ")
                .containsEntry("options", List.of())
                .containsEntry("answer", null)
                .containsEntry("remaining", 1)
                .containsEntry("maxTurns", 2);

    }

    @Test
    @DisplayName("저장되는 질문은 요청한 칸과 AI 가 준 종류·글을 그대로 담고, 질문한 쪽은 AI 가 AUTO 를 줘도 USER 이며 부모·답·결과는 비어 있다")
    void savesTheQuestionAsAskedByUser() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        AI.replyWith(request -> FakeInterviewAi.askAgain(cardId, "A", "FOLLOWUP", "AUTO", "이어서 물어요"));
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        ask(myUserId, MY_CARD.toString(), "A").andExpect(status().isOk());

        Instant after = Instant.now();
        List<TurnRow> saved = interviews.turnsOf(cardId);
        assertThat(saved).hasSize(1);
        TurnRow row = saved.get(0);
        assertThat(row.seq()).isEqualTo(1);
        assertThat(row.starSlot()).isEqualTo("A");
        assertThat(row.questionType()).isEqualTo("FOLLOWUP");
        assertThat(row.triggerSource()).isEqualTo("USER");
        assertThat(row.parentTurnId()).isNull();
        assertThat(row.questionText()).isEqualTo("이어서 물어요");
        assertThat(row.answerText()).isNull();
        assertThat(row.answerSource()).isNull();
        assertThat(row.outcome()).isNull();
        assertThat(row.askedAt()).isBetween(before, after);

    }

    @Test
    @DisplayName("새 질문은 이미 답한 질문 다음 번호로 저장된다")
    void numbersTheQuestionAfterTheLastOne() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");

        ask(myUserId, MY_CARD.toString(), "T").andExpect(status().isOk());

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::seq, TurnRow::starSlot)
                .containsExactly(tuple(1, "S"), tuple(2, "T"));

    }

    @Test
    @DisplayName("만든 질문은 되묻기 기록 조회에 같은 번호·질문으로 나온다")
    void newQuestionAppearsInHistory() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isOk());

        mvc.perform(get(ASK_PATH, MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].turnNo").value(1))
                .andExpect(jsonPath("$.data[0].askedBy").value("USER_REQUEST"));

    }

    @Test
    @DisplayName("질문을 만들어도 카드 행과 버전·문장은 그대로여서 카드를 마지막으로 고친 시각이 바뀌지 않는다")
    void doesNotTouchTheCard() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "상황 글", "USER_STATED", null);
        List<Map<String, Object>> before = cardState(cardId);

        ask(myUserId, MY_CARD.toString(), "T").andExpect(status().isOk());

        assertThat(cardState(cardId)).isEqualTo(before);
        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    @Test
    @DisplayName("AI 가 다시 시도해도 되는 실패를 주면 503 AI_UNAVAILABLE 이고 질문이 만들어지지 않으며, AI 가 돌아오면 같은 요청이 질문을 만든다")
    void retryableAiFailureIsServiceUnavailable() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        AI.replyWith(request -> FakeInterviewAi.failure(503, "LLM_UNAVAILABLE", true));

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

        assertThat(interviews.turnsOf(cardId)).isEmpty();
        assertThat(events()).isEmpty();

        AI.replyWith(FakeInterviewAi::askAgain);
        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnNo").value(1));

    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableReplies")
    @DisplayName("AI 응답이 이상하거나 쓸 수 없으면 500 INTERNAL_ERROR 이고 질문이 만들어지지 않는다")
    void unusableAiResponseIsInternalError(Function<Long, FakeInterviewAi.Reply> reply) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        AI.replyWith(request -> reply.apply(cardId));

        ask(myUserId, MY_CARD.toString(), "T")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertThat(AI.requests()).hasSize(1);
        assertThat(interviews.turnsOf(cardId)).isEmpty();

    }

    @Test
    @DisplayName("묻는 칸이 비어 있으면 카드 번호·직접 작성 출처·빈 후보·칸 하나(글 null·LOW·근거 커밋 없음)·질문 수 0·상한 2 를 AI 에 보낸다")
    void sendsEmptySlotContext() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);

        ask(myUserId, MY_CARD.toString(), "T").andExpect(status().isOk());

        JsonNode expected = MAPPER.readTree("""
                {"card_id": %d, "candidate": null,
                 "missing_slots": [{"star_slot": "T", "statement_seq": 1, "body": null, "confidence": "LOW",
                                    "linked_commits": []}],
                 "existing_turn_count": 0, "max_turns": 2, "source_type": "DIRECT_CARD"}
                """.formatted(cardId));
        assertThat((Object) sentRequest()).isEqualTo(expected);

    }

    @Test
    @DisplayName("사용자가 쓴 칸은 확신도가 비어 있어도 저장된 글을 앞뒤 공백까지 그대로, 확신도 HIGH 로 보낸다")
    void sendsUserStatedSlotExactlyAsHigh() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "T", "  앞뒤 공백이 있는 글  ", "USER_STATED", null);

        ask(myUserId, MY_CARD.toString(), "T").andExpect(status().isOk());

        JsonNode slot = sentRequest().path("missing_slots").path(0);
        assertThat(slot.path("body").asString()).isEqualTo("  앞뒤 공백이 있는 글  ");
        assertThat(slot.path("confidence").asString()).isEqualTo("HIGH");

    }

    @Test
    @DisplayName("저장된 확신도(MEDIUM)가 있으면 그대로 보낸다")
    void sendsStoredConfidenceAsStored() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "T", "커밋에서 읽은 글", "COMMIT", "MEDIUM");

        ask(myUserId, MY_CARD.toString(), "T").andExpect(status().isOk());

        assertThat(sentRequest().path("missing_slots").path(0).path("confidence").asString()).isEqualTo("MEDIUM");

    }

    @Test
    @DisplayName("지금까지 만든 질문 수는 답한 질문도 세어 existing_turn_count 로 보낸다")
    void countsAnsweredTurnInExistingTurnCount() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAnswered(cardId, 1, "S", "첫 답", "TYPED");

        ask(myUserId, MY_CARD.toString(), "T").andExpect(status().isOk());

        assertThat(sentRequest().path("existing_turn_count").intValue()).isEqualTo(1);
        assertThat(sentRequest().path("max_turns").intValue()).isEqualTo(2);

    }

    @Test
    @DisplayName("OpenAPI 문서는 질문 요청의 200·400·401·403·404·409·503 응답을 우리가 쓴 설명으로 적고 로그인 사용자는 요청 변수로 보이지 않는다")
    void documentsEveryStatus() throws Exception {

        // 오류 처리기의 상태 코드는 springdoc 이 설명 없이도 응답으로 올려서, 응답이 있는지가 아니라 우리가 쓴 설명이 실렸는지를 본다
        String post = "$.paths['/api/cards/{id}/interview'].post";
        mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(post + ".summary").value("되묻기 질문 요청"))
                .andExpect(jsonPath(post + ".parameters.length()").value(1))
                .andExpect(jsonPath(post + ".parameters[0].schema.format").value("uuid"))
                .andExpect(jsonPath(post + ".requestBody").exists())
                .andExpect(jsonPath(post + ".responses['200'].description").value(containsString("답하지 않은 채 남아 있던 질문")))
                .andExpect(jsonPath(post + ".responses['400'].description").value(containsString("BAD_FIELD")))
                .andExpect(jsonPath(post + ".responses['400'].description").value(containsString("INVALID_REQUEST")))
                .andExpect(jsonPath(post + ".responses['401'].description").value(containsString("로그인 세션 없음")))
                .andExpect(jsonPath(post + ".responses['403'].description").value(containsString("CSRF")))
                .andExpect(jsonPath(post + ".responses['404'].description").value(containsString("본인 카드 없음")))
                .andExpect(jsonPath(post + ".responses['409'].description").value(containsString("CARD_CONFIRMED")))
                .andExpect(jsonPath(post + ".responses['409'].description").value(containsString("INTERVIEW_CAP")))
                .andExpect(jsonPath(post + ".responses['503'].description").value(containsString("AI_UNAVAILABLE")));

    }

    static Stream<Named<String>> invalidBodies() {

        return Stream.of(
                Named.of("키 없음", "{}"),
                Named.of("null", "{\"field\":null}"),
                Named.of("S·T·A·R 가 아닌 글자", "{\"field\":\"X\"}"),
                Named.of("소문자", "{\"field\":\"s\"}"),
                Named.of("앞 공백", "{\"field\":\" S\"}"),
                Named.of("뒤 공백", "{\"field\":\"S \"}"),
                Named.of("빈 글", "{\"field\":\"\"}"),
                Named.of("두 글자", "{\"field\":\"ST\"}"),
                Named.of("숫자", "{\"field\":1}"));

    }

    static Stream<Arguments> unusableReplies() {

        return Stream.of(
                scenario("AI 가 요청이 틀렸다고 답함(400)",
                        cardId -> FakeInterviewAi.failure(400, "INVALID_PAYLOAD", false)),
                scenario("AI 가 고장 났다고 답함(500)",
                        cardId -> FakeInterviewAi.failure(500, "INTERNAL_ERROR", false)),
                scenario("응답의 card_id 가 요청과 다름",
                        cardId -> FakeInterviewAi.askAgain(cardId + 1, "T", "EVIDENCE_GAP", "AUTO", "질문")),
                scenario("질문을 더 만들 수 있는데 COMPLETE", FakeInterviewAi::complete),
                scenario("요청하지 않은 칸의 질문",
                        cardId -> FakeInterviewAi.askAgain(cardId, "A", "EVIDENCE_GAP", "AUTO", "질문")));

    }

    private static Arguments scenario(String name, Function<Long, FakeInterviewAi.Reply> reply) {

        return arguments(Named.of(name, reply));

    }

    private ResultActions ask(Long userId, String id, String field) throws Exception {

        return send(userId, id, body(field));

    }

    private ResultActions send(Long userId, String id, String content) throws Exception {

        return mvc.perform(askRequest(id, content).with(loggedInAs(userId)).with(csrf()));

    }

    private static MockHttpServletRequestBuilder askRequest(String id, String content) {

        return post(ASK_PATH, id).contentType(MediaType.APPLICATION_JSON).content(content);

    }

    private static String body(String field) {

        return "{\"field\":\"" + field + "\"}";

    }

    private Long confirmedCard() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(cardId);

        return cardId;

    }

    private Long othersCard() {

        // 남의 카드 id 가 내 사용자 id 와 같아지면 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 먼저 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);

        return fixtures.insertCard(OTHERS_CARD, othersUserId);

    }

    // 소유 확인이 상태 확인보다 늦으면 남의 카드의 상태가 응답으로 새므로, 어떤 상태든 없는 카드와 같은 404 여야 한다
    private void expectDeniedAsNotFound(Long othersCardId, String field) throws Exception {

        List<TurnRow> before = interviews.turnsOf(othersCardId);

        ask(myUserId, OTHERS_CARD.toString(), field)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED"));
        assertThat(interviews.turnsOf(othersCardId)).isEqualTo(before);
        assertThat(AI.requests()).isEmpty();

    }

    private static JsonNode sentRequest() {

        assertThat(AI.requests()).hasSize(1);

        return AI.requests().get(0);

    }

    private List<Map<String, Object>> cardState(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        // 배열 칸(areas·techs)은 JDBC 배열 객체로 읽혀 값이 같아도 같다고 비교되지 않으므로, 카드 행은 JSON 글로 읽는다
        rows.addAll(jdbc.queryForList("SELECT to_jsonb(card)::text AS card FROM card WHERE id = ?", cardId));
        rows.addAll(jdbc.queryForList("SELECT * FROM card_version WHERE card_id = ? ORDER BY version_no", cardId));
        rows.addAll(jdbc.queryForList("SELECT * FROM card_statement WHERE card_id = ? ORDER BY id", cardId));

        return rows;

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
