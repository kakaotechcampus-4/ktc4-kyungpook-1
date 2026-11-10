package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.InterviewFixtures.TurnRow;
import com.gitory.backend.support.TestFixtures;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.nullValue;
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
        "gitory.interview.max-turns=2"
})
@AutoConfigureMockMvc
@Testcontainers
class InterviewReplyApiTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final FakeInterviewAi AI = FakeInterviewAi.start();
    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String PATH = "/api/cards/{id}/interview/{turnNo}/reply";
    private static final String QUESTION = "배포가 늦어질 때 직접 하신 일이 무엇이었나요?";
    private static final String SITUATION = "배포가 늦어져 팀이 수정 사항을 몰아서 올리던 때였다";
    private static final String TYPED = "로그를 보니 전체를 다시 빌드하길래 바뀐 모듈만 빌드하게 고쳐서 12분에서 4분으로 줄였어요";
    private static final String SENTENCE = "배포 로그에서 매번 전체를 빌드하는 것을 확인해 바뀐 모듈만 빌드하도록 설정을 고쳤다.";

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
    @DisplayName("쓸 내용이 있는 답이면 AI 가 쓴 문장을 그 칸의 글로 넣고 사용자가 친 답은 다듬기 전 답으로 남긴다")
    void appliesTheSentenceAndKeepsTheTypedAnswerAsOriginal() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> apply(request, SENTENCE));

        reply(1, "  " + TYPED + "  ", "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.data.reason").value(nullValue()))
                .andExpect(jsonPath("$.data.written.length()").value(1))
                .andExpect(jsonPath("$.data.written[0].field").value("A"))
                .andExpect(jsonPath("$.data.written[0].text").value(SENTENCE))
                .andExpect(jsonPath("$.data.restoreVersionNo").value(1))
                .andExpect(jsonPath("$.data.nextTurn").value(nullValue()))
                .andExpect(jsonPath("$.data.card.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.card.version.situation").value(SITUATION))
                .andExpect(jsonPath("$.data.card.version.action").value(SENTENCE));

        JsonNode sent = AI.requests().get(0);
        assertThat(sent.path("card_id").longValue()).isEqualTo(cardId);
        assertThat(sent.path("card_title").asString()).isEqualTo("카드");
        assertThat(sent.path("star_slot").asString()).isEqualTo("A");
        assertThat(sent.path("question_text").asString()).isEqualTo(QUESTION);
        assertThat(sent.path("answer_text").asString()).isEqualTo(TYPED);
        assertThat(sent.path("held_answers")).isEmpty();
        assertThat(sent.path("follow_up_allowed").asBoolean()).isTrue();
        assertThat(sent.path("slots")).extracting(slot -> slot.path("star_slot").asString())
                .containsExactly("S", "T", "A", "R");
        assertThat(sent.path("slots").get(0).path("body").asString()).isEqualTo(SITUATION);
        assertThat(sent.path("slots").get(2).path("body").isNull()).isTrue();
        assertThat(rowsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText, TurnRow::answerSource)
                .containsExactly(tuple("ANSWERED", SENTENCE, "TYPED"));
        assertThat(jdbc.queryForObject("SELECT answer_original FROM interview_turn WHERE card_id = ?", String.class,
                cardId)).isEqualTo("  " + TYPED + "  ");
        assertThat(jdbc.queryForList("SELECT star_slot, body, evidence_type FROM card_statement WHERE card_id = ? AND version_no = 2 ORDER BY star_slot",
                cardId)).extracting(row -> row.get("star_slot"), row -> row.get("body"), row -> row.get("evidence_type"))
                .containsExactly(tuple("A", SENTENCE, "USER_STATED"), tuple("S", SITUATION, "USER_STATED"));
        assertThat(jdbc.queryForObject("SELECT source FROM card_version WHERE card_id = ? AND version_no = 2",
                String.class, cardId)).isEqualTo("INTERVIEW");

    }

    @Test
    @DisplayName("다른 칸의 문장이 같이 오면 지금 비어 있는 칸에만 넣고 같은 답의 근거로 이어 둔다")
    void writesExtrasOnlyIntoEmptySlots() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> apply(request, SENTENCE, extra("R", "배포 시간이 12분에서 4분으로 줄었다.")));

        reply(1, TYPED, "USER_SELECTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.written.length()").value(2))
                .andExpect(jsonPath("$.data.written[0].field").value("A"))
                .andExpect(jsonPath("$.data.written[1].field").value("R"))
                .andExpect(jsonPath("$.data.written[1].text").value("배포 시간이 12분에서 4분으로 줄었다."))
                .andExpect(jsonPath("$.data.card.version.result").value("배포 시간이 12분에서 4분으로 줄었다."));

        Long turnId = jdbc.queryForObject("SELECT id FROM interview_turn WHERE card_id = ?", Long.class, cardId);
        assertThat(jdbc.queryForList("SELECT star_slot, evidence_type, source_turn_id FROM card_statement WHERE card_id = ? AND version_no = 2 AND star_slot IN ('A', 'R') ORDER BY star_slot",
                cardId)).extracting(row -> row.get("star_slot"), row -> row.get("evidence_type"),
                row -> row.get("source_turn_id"))
                .containsExactly(tuple("A", "USER_SELECTED", turnId), tuple("R", "USER_SELECTED", turnId));

    }

    @ParameterizedTest
    @MethodSource("extrasToIgnore")
    @DisplayName("물은 칸·글이 있는 칸·겹친 칸의 문장이나 빈 문장·긴 문장·답에 없는 숫자가 든 문장은 넣지 않는다")
    void ignoresUnusableExtras(List<ObjectNode> extras) throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> apply(request, SENTENCE, extras.toArray(ObjectNode[]::new)));

        reply(1, TYPED, "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.data.written.length()").value(1))
                .andExpect(jsonPath("$.data.card.version.result").value(nullValue()));

        assertThat(jdbc.queryForList("SELECT star_slot FROM card_statement WHERE card_id = ? AND version_no = 2 ORDER BY star_slot",
                String.class, cardId)).containsExactly("A", "S");

    }

    @Test
    @DisplayName("모자란 답이면 카드를 바꾸지 않고 그 질문을 모자란 답으로 닫은 뒤 같은 칸의 꼬리 질문을 만들어 돌려준다")
    void asksMoreWithAFollowUpOnTheSameSlot() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> askMore(request, "어떤 설정을 어떻게 바꾸셨는지 한 가지만 더 알려 주세요.",
                "어떤 설정을 어떻게 고쳤는지가 빠져 있어요"));

        reply(1, "설정을 좀 고쳤어요", "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ASK_MORE"))
                .andExpect(jsonPath("$.data.reason").value("어떤 설정을 어떻게 고쳤는지가 빠져 있어요"))
                .andExpect(jsonPath("$.data.written.length()").value(0))
                .andExpect(jsonPath("$.data.restoreVersionNo").value(nullValue()))
                .andExpect(jsonPath("$.data.card.version.versionNo").value(1))
                .andExpect(jsonPath("$.data.nextTurn.turnNo").value(2))
                .andExpect(jsonPath("$.data.nextTurn.field").value("A"))
                .andExpect(jsonPath("$.data.nextTurn.questionType").value("FOLLOWUP"))
                .andExpect(jsonPath("$.data.nextTurn.askedBy").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.data.nextTurn.question").value("어떤 설정을 어떻게 바꾸셨는지 한 가지만 더 알려 주세요."))
                .andExpect(jsonPath("$.data.nextTurn.reason").value("어떤 설정을 어떻게 고쳤는지가 빠져 있어요"))
                .andExpect(jsonPath("$.data.nextTurn.outcome").value(nullValue()));

        List<TurnRow> rows = rowsOf(cardId);
        assertThat(rows).extracting(TurnRow::seq, TurnRow::outcome, TurnRow::answerText, TurnRow::answerSource)
                .containsExactly(tuple(1, "INSUFFICIENT", "설정을 좀 고쳤어요", "TYPED"), tuple(2, null, null, null));
        assertThat(rows.get(1).parentTurnId()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM interview_turn WHERE card_id = ? AND seq = 1", Long.class, cardId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("꼬리 질문에 답하면 앞서 둔 답을 AI 에 함께 보내고 더 되묻지 않게 한다")
    void followUpAnswerSendsHeldAnswersAndForbidsAnotherFollowUp() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        Long first = jdbc.queryForObject("SELECT id FROM interview_turn WHERE card_id = ?", Long.class, cardId);
        jdbc.update("UPDATE interview_turn SET outcome = 'INSUFFICIENT', answer_text = '설정을 좀 고쳤어요', answer_source = 'TYPED', answered_at = now() WHERE id = ?",
                first);
        Long second = interviews.insertAsked(cardId, 2, "A", "FOLLOWUP", "AUTO", "어떤 설정이었나요?");
        jdbc.update("UPDATE interview_turn SET parent_turn_id = ? WHERE id = ?", first, second);
        AI.replyWith(request -> apply(request, "빌드 설정에서 바뀐 모듈만 다시 빌드하도록 고쳤다."));

        reply(2, "바뀐 모듈만 빌드하게 했어요", "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("APPLIED"));

        JsonNode sent = AI.requests().get(0);
        assertThat(sent.path("held_answers")).extracting(JsonNode::asString).containsExactly("설정을 좀 고쳤어요");
        assertThat(sent.path("follow_up_allowed").asBoolean()).isFalse();

    }

    @Test
    @DisplayName("질문 상한에 닿았으면 더 되묻지 않게 하고, AI 가 그래도 되물으면 아무것도 저장하지 않고 다듬지 못했다고 답한다")
    void doesNotAskMoreAtTheQuestionCap() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", SITUATION, "USER_STATED", null);
        interviews.insertAnswered(cardId, 1, "T", "과제 답", "TYPED");
        interviews.insertAsked(cardId, 2, "A", "EVIDENCE_GAP", "USER", QUESTION);
        AI.replyWith(request -> askMore(request, "한 가지만 더 알려 주세요.", "빠진 게 있어요"));

        reply(2, "설정을 좀 고쳤어요", "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("UNPOLISHED"))
                .andExpect(jsonPath("$.data.nextTurn").value(nullValue()));

        assertThat(AI.requests().get(0).path("follow_up_allowed").asBoolean()).isFalse();
        assertThat(rowsOf(cardId)).extracting(TurnRow::seq, TurnRow::outcome)
                .containsExactly(tuple(1, "ANSWERED"), tuple(2, null));

    }

    @Test
    @DisplayName("상관없는 답이면 아무것도 저장하지 않고 질문은 열린 채로 둔다")
    void offTopicSavesNothing() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> verdictOnly(request, "OFF_TOPIC", "질문과 상관없는 말이에요"));

        reply(1, "오늘 점심 뭐 먹지", "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("NOT_USED"))
                .andExpect(jsonPath("$.data.reason").value("질문과 상관없는 말이에요"))
                .andExpect(jsonPath("$.data.written.length()").value(0));

        assertThat(rowsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText).containsExactly(tuple(null, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("모른다는 답이면 그 질문을 넘김으로 닫고 카드는 바꾸지 않는다")
    void dontKnowSkipsTheTurn() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> verdictOnly(request, "DONT_KNOW", null));

        reply(1, "잘 모르겠어요", "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.data.card.version.versionNo").value(1));

        assertThat(rowsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText).containsExactly(tuple("SKIPPED", null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("AI 가 답하지 못하면 아무것도 저장하지 않고 200 으로 다듬지 못했다고 답한다")
    void aiFailureIsUnpolishedWithoutSaving() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> FakeInterviewAi.failure(503, "LLM_UNAVAILABLE", true));

        reply(1, TYPED, "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("UNPOLISHED"))
                .andExpect(jsonPath("$.data.written.length()").value(0))
                .andExpect(jsonPath("$.data.card.version.versionNo").value(1));

        assertThat(rowsOf(cardId)).extracting(TurnRow::outcome).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(1);

    }

    @ParameterizedTest
    @MethodSource("unusableVerdicts")
    @DisplayName("AI 가 쓴 문장에 답에 없는 숫자가 있거나 비었거나 칸에 못 담을 길이거나 꼬리 질문이 비었으면 넣지 않고 다듬지 못했다고 답한다")
    void unusableVerdictIsUnpolished(Function<JsonNode, FakeInterviewAi.Reply> verdict) throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(verdict);

        reply(1, TYPED, "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("UNPOLISHED"))
                .andExpect(jsonPath("$.data.nextTurn").value(nullValue()));

        assertThat(rowsOf(cardId)).extracting(TurnRow::outcome).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("문장의 숫자가 앞서 둔 답이나 그 칸의 기존 글에 있으면 쓸 수 있다")
    void numbersFromHeldAnswersAndExistingSlotTextAreAllowed() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        fixtures.insertStatement(cardId, 1, "A", "예전에 쓴 글 7곳", "USER_STATED", null);
        Long first = jdbc.queryForObject("SELECT id FROM interview_turn WHERE card_id = ?", Long.class, cardId);
        jdbc.update("UPDATE interview_turn SET outcome = 'INSUFFICIENT', answer_text = '배포를 3번 했어요', answer_source = 'TYPED', answered_at = now() WHERE id = ?",
                first);
        Long second = interviews.insertAsked(cardId, 2, "A", "FOLLOWUP", "AUTO", "어떤 설정이었나요?");
        jdbc.update("UPDATE interview_turn SET parent_turn_id = ? WHERE id = ?", first, second);
        AI.replyWith(request -> apply(request, "배포를 3번 하며 7곳의 설정을 고쳤다."));

        reply(2, "설정을 고쳤어요", "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("APPLIED"));

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 카드의 현재 버전이 바뀌었으면 넣지 않고 409 로 답한다")
    void changedCardWhileWaitingIsConflict() throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(request -> apply(request, SENTENCE));
        AI.beforeReply(request -> fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false));

        reply(1, TYPED, "USER_STATED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(rowsOf(cardId)).extracting(TurnRow::outcome).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(2);

    }

    @ParameterizedTest
    @MethodSource("decidingVerdicts")
    @DisplayName("이미 끝난 질문에 같은 답이 다시 오면 아무것도 바꾸지 않고 같은 결과를 돌려주며 AI 를 다시 부르지 않는다")
    void sameAnswerAgainReturnsTheSameResultWithoutChanges(Function<JsonNode, FakeInterviewAi.Reply> verdict,
                                                           String outcome) throws Exception {

        Long cardId = cardWithOpenTurn(true);
        AI.replyWith(verdict);
        reply(1, TYPED, "USER_STATED").andExpect(status().isOk());
        int requestsBefore = replyRequests().size();
        int versionsBefore = jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class,
                cardId);
        List<TurnRow> turnsBefore = rowsOf(cardId);

        reply(1, TYPED, "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value(outcome))
                .andExpect(jsonPath("$.data.restoreVersionNo").value(nullValue()));

        assertThat(replyRequests()).hasSize(requestsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(versionsBefore);
        assertThat(rowsOf(cardId)).isEqualTo(turnsBefore);

    }

    @Test
    @DisplayName("이미 끝난 질문에 다른 답이 오면 409 INVALID_STATE 이고 AI 를 부르지 않는다")
    void differentAnswerOnAFinishedTurnIsConflict() throws Exception {

        cardWithOpenTurn(true);
        AI.replyWith(request -> apply(request, SENTENCE));
        reply(1, TYPED, "USER_STATED").andExpect(status().isOk());

        reply(1, "다른 답이에요", "USER_STATED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(replyRequests()).hasSize(1);

    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("넣은 뒤 상황과 행동이 차 있으면 카드가 확정되고, 상황이 비어 있으면 작성 중으로 남는다")
    void settlesTheCardAfterApplying(boolean situationFilled) throws Exception {

        cardWithOpenTurn(situationFilled);
        AI.replyWith(request -> apply(request, SENTENCE));

        reply(1, TYPED, "USER_STATED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.status").value(situationFilled ? "CONFIRMED" : "DRAFT"));

    }

    @Test
    @DisplayName("응답의 되돌릴 버전 번호로 되돌리면 넣기 전 글로 돌아간다")
    void restoreVersionNoGoesBackToTheTextBeforeWriting() throws Exception {

        cardWithOpenTurn(true);
        fixtures.insertStatement(jdbc.queryForObject("SELECT id FROM card WHERE public_id = ?", Long.class, MY_CARD),
                1, "R", "원래 있던 결과 글", "USER_STATED", null);
        AI.replyWith(request -> apply(request, SENTENCE));
        int restoreVersionNo = MAPPER.readTree(reply(1, TYPED, "USER_STATED").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("data").path("restoreVersionNo").asInt();

        mvc.perform(post("/api/cards/{id}/versions/{no}/restore", MY_CARD, restoreVersionNo)
                        .with(loggedIn(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.source").value("RESTORE"))
                .andExpect(jsonPath("$.data.version.action").value(nullValue()))
                .andExpect(jsonPath("$.data.version.result").value("원래 있던 결과 글"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "unknown"})
    @DisplayName("남의 카드나 없는 질문 번호면 AI 를 부르지 않고 404 다")
    void othersCardOrUnknownTurnIsNotFound(String which) throws Exception {

        cardWithOpenTurn(true);
        Long othersCard = fixtures.insertCard(OTHERS_CARD, othersUserId);
        interviews.insertAsked(othersCard, 1, "A", "EVIDENCE_GAP", "USER", QUESTION);

        mvc.perform(post(PATH, which.equals("other") ? OTHERS_CARD : MY_CARD, which.equals("other") ? 1 : 9)
                        .contentType(MediaType.APPLICATION_JSON).content(body(TYPED, "USER_STATED"))
                        .with(loggedIn(myUserId)).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(AI.requests()).isEmpty();

    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("답이 비었거나 1,000자를 넘거나 출처가 틀리면 400 INVALID_REQUEST 이고 AI 를 부르지 않는다")
    void invalidBodyIsBadRequest(String text, String source) throws Exception {

        cardWithOpenTurn(true);

        reply(1, text, source)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(AI.requests()).isEmpty();

    }

    static Stream<Arguments> extrasToIgnore() {

        return Stream.of(
                Arguments.of(Named.of("물은 칸", List.of(extra("A", "물은 칸의 다른 문장")))),
                Arguments.of(Named.of("글이 있는 칸", List.of(extra("S", "이미 글이 있는 칸의 문장")))),
                Arguments.of(Named.of("답에 없는 숫자", List.of(extra("R", "배포 시간이 20분 줄었다.")))),
                Arguments.of(Named.of("빈 문장", List.of(extra("R", "   ")))),
                Arguments.of(Named.of("1,001자", List.of(extra("R", "가".repeat(1001))))));

    }

    static Stream<Arguments> unusableVerdicts() {

        Function<JsonNode, FakeInterviewAi.Reply> invented = request -> apply(request, "배포 시간을 99분 줄였다.");
        Function<JsonNode, FakeInterviewAi.Reply> blank = request -> apply(request, "   ");
        Function<JsonNode, FakeInterviewAi.Reply> tooLong = request -> apply(request, "가".repeat(1001));
        Function<JsonNode, FakeInterviewAi.Reply> blankFollowUp = request -> askMore(request, "  ", "빠진 게 있어요");

        return Stream.of(
                Arguments.of(Named.of("답에 없는 숫자", invented)),
                Arguments.of(Named.of("빈 문장", blank)),
                Arguments.of(Named.of("1,001자 문장", tooLong)),
                Arguments.of(Named.of("빈 꼬리 질문", blankFollowUp)));

    }

    static Stream<Arguments> decidingVerdicts() {

        Function<JsonNode, FakeInterviewAi.Reply> apply = request -> apply(request, SENTENCE);
        Function<JsonNode, FakeInterviewAi.Reply> askMore = request -> askMore(request, "한 가지만 더 알려 주세요.",
                "빠진 게 있어요");
        Function<JsonNode, FakeInterviewAi.Reply> dontKnow = request -> verdictOnly(request, "DONT_KNOW", null);

        return Stream.of(
                Arguments.of(Named.of("넣은 답", apply), "APPLIED"),
                Arguments.of(Named.of("모자란 답", askMore), "ASK_MORE"),
                Arguments.of(Named.of("넘긴 질문", dontKnow), "SKIPPED"));

    }

    static Stream<Arguments> invalidBodies() {

        return Stream.of(
                Arguments.of(Named.of("공백뿐", "   "), "USER_STATED"),
                Arguments.of(Named.of("1,001자", "가".repeat(1001)), "USER_STATED"),
                Arguments.of(Named.of("모르는 출처", TYPED), "AI_WRITTEN"));

    }

    private static ObjectNode extra(String slot, String sentence) {

        return MAPPER.createObjectNode().put("star_slot", slot).put("sentence", sentence);

    }

    private static FakeInterviewAi.Reply apply(JsonNode request, String sentence, ObjectNode... extras) {

        ArrayNode extra = MAPPER.createArrayNode();
        for (ObjectNode each : extras) {
            extra.add(each);
        }
        ObjectNode data = MAPPER.createObjectNode().put("card_id", request.path("card_id").longValue())
                .put("verdict", "APPLY").put("sentence", sentence);
        data.set("extra", extra);
        data.putNull("follow_up");
        data.putNull("reason");

        return FakeInterviewAi.success(data);

    }

    private static FakeInterviewAi.Reply askMore(JsonNode request, String followUp, String reason) {

        ObjectNode data = MAPPER.createObjectNode().put("card_id", request.path("card_id").longValue())
                .put("verdict", "ASK_MORE").putNull("sentence");
        data.set("extra", MAPPER.createArrayNode());
        data.put("follow_up", followUp).put("reason", reason);

        return FakeInterviewAi.success(data);

    }

    private static FakeInterviewAi.Reply verdictOnly(JsonNode request, String verdict, String reason) {

        ObjectNode data = MAPPER.createObjectNode().put("card_id", request.path("card_id").longValue())
                .put("verdict", verdict).putNull("sentence");
        data.set("extra", MAPPER.createArrayNode());
        data.putNull("follow_up");
        if (reason == null) {
            data.putNull("reason");
        } else {
            data.put("reason", reason);
        }

        return FakeInterviewAi.success(data);

    }

    private Long cardWithOpenTurn(boolean situationFilled) {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        if (situationFilled) {
            fixtures.insertStatement(cardId, 1, "S", SITUATION, "USER_STATED", null);
        }
        interviews.insertAsked(cardId, 1, "A", "EVIDENCE_GAP", "USER", QUESTION);

        return cardId;

    }

    // 카드가 확정되면 문항 유형을 묻는 AI 호출이 따로 가므로 되묻기 답 요청만 센다
    private static List<JsonNode> replyRequests() {

        return AI.requests().stream().filter(request -> request.has("question_text")).toList();

    }

    private List<TurnRow> rowsOf(Long cardId) {

        return new ArrayList<>(interviews.turnsOf(cardId));

    }

    private String body(String text, String source) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", text);
        fields.put("source", source);

        return MAPPER.writeValueAsString(fields);

    }

    private ResultActions reply(int turnNo, String text, String source) throws Exception {

        return mvc.perform(post(PATH, MY_CARD, turnNo).contentType(MediaType.APPLICATION_JSON)
                .content(body(text, source)).with(loggedIn(myUserId)).with(csrf()));

    }

    private RequestPostProcessor loggedIn(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
