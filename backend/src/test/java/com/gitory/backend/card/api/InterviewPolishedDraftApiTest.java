package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class InterviewPolishedDraftApiTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final FakeInterviewAi AI = FakeInterviewAi.start();
    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String PATH = "/api/cards/{id}/interview/{turnNo}/polished-draft";
    private static final String QUESTION = "그때 어떤 상황이었나요?";

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

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);
        fixtures.clear();
        AI.reset();

        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("경험 카드의 글 질문에 쓴 답을 앞뒤 공백을 뺀 다듬은 문장으로 돌려주고, 답에 없는 숫자를 알리며 아무것도 저장하지 않는다")
    void returnsThePolishedSentenceWithoutSaving() throws Exception {

        Long cardId = cardWithOpenTurn();
        AI.replyWith(request -> polished(request, " 회의를 3번 열어 시간을 50% 줄였다.\n"));

        polish("  회의를 3번 열었더니 시간이 줄었어요  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sentence").value("회의를 3번 열어 시간을 50% 줄였다."))
                .andExpect(jsonPath("$.data.polished").value(true))
                .andExpect(jsonPath("$.data.unsourcedNumbers[0]").value("50"))
                .andExpect(jsonPath("$.data.unsourcedNumbers.length()").value(1));

        JsonNode sent = AI.requests().get(0);
        assertThat(sent.path("card_id").longValue()).isEqualTo(cardId);
        assertThat(sent.path("star_slot").asString()).isEqualTo("S");
        assertThat(sent.path("question_text").asString()).isEqualTo(QUESTION);
        assertThat(sent.path("answer_text").asString()).isEqualTo("회의를 3번 열었더니 시간이 줄었어요");
        assertThat(jdbc.queryForObject("SELECT outcome FROM interview_turn WHERE card_id = ?", String.class, cardId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("숫자는 쉼표를 빼고 답의 숫자와 견주며, 답에 없는 숫자를 다듬은 문장에 나온 순서대로 한 번씩 알린다")
    void unsourcedNumbersAreComparedWithoutCommas() throws Exception {

        cardWithOpenTurn();
        AI.replyWith(request -> polished(request, "회의를 1,000번 열어 1500명이 왔고 50% 줄였다. 50명이 남아 7일 더 했다."));

        polish("회의를 1000번 열었고 1,500명이 왔어요")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unsourcedNumbers.length()").value(2))
                .andExpect(jsonPath("$.data.unsourcedNumbers[0]").value("50"))
                .andExpect(jsonPath("$.data.unsourcedNumbers[1]").value("7"));

    }

    @ParameterizedTest
    @MethodSource("unusableReplies")
    @DisplayName("AI 가 응답하지 못하거나 다른 카드의 답·빈 문장·칸에 못 담을 길이를 주면 오류 대신 사용자 답을 그대로 돌려준다")
    void returnsTheAnswerAsIsWhenItCannotPolish(Function<JsonNode, FakeInterviewAi.Reply> reply) throws Exception {

        cardWithOpenTurn();
        AI.replyWith(reply);

        polish("  회의 시간이 줄었어요 ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sentence").value("회의 시간이 줄었어요"))
                .andExpect(jsonPath("$.data.polished").value(false))
                .andExpect(jsonPath("$.data.unsourcedNumbers").isEmpty());

    }

    @ParameterizedTest
    @MethodSource("questionsNotToPolish")
    @DisplayName("기술 카드의 질문이나 수치 질문은 다듬지 않아 400 BAD_FIELD 이고 AI 를 부르지 않는다")
    void onlyTextQuestionsOfExperienceCardsArePolished(String update) throws Exception {

        Long cardId = cardWithOpenTurn();
        jdbc.update(update, cardId);

        polish("회의 시간이 줄었어요")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_FIELD"));

        assertThat(AI.requests()).isEmpty();

    }

    @ParameterizedTest
    @MethodSource("invalidTexts")
    @DisplayName("답이 비었거나 1,000자를 넘거나 U+0000 이 있으면 400 INVALID_REQUEST 이고 AI 를 부르지 않는다")
    void invalidTextIsRejected(String text) throws Exception {

        cardWithOpenTurn();

        polish(text)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(AI.requests()).isEmpty();

    }

    @ParameterizedTest
    @MethodSource("conflicts")
    @DisplayName("결과가 정해진 질문은 409 INVALID_STATE, 확정된 카드는 409 CARD_CONFIRMED 이고 AI 를 부르지 않는다")
    void closedTurnOrConfirmedCardIsConflict(String update, String code) throws Exception {

        Long cardId = cardWithOpenTurn();
        jdbc.update(update, cardId);

        polish("회의 시간이 줄었어요")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(code));

        assertThat(AI.requests()).isEmpty();

    }

    @Test
    @DisplayName("없는 질문 번호는 404 NOT_FOUND 이고 AI 를 부르지 않는다")
    void unknownTurnIsNotFound() throws Exception {

        cardWithOpenTurn();

        mvc.perform(post(PATH, MY_CARD, 9).contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("text", "회의 시간이 줄었어요")))
                        .with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(AI.requests()).isEmpty();

    }

    static Stream<Arguments> unusableReplies() {

        Function<JsonNode, FakeInterviewAi.Reply> failure = request -> FakeInterviewAi.failure(503, "LLM_UNAVAILABLE", true);
        Function<JsonNode, FakeInterviewAi.Reply> otherCard = request -> FakeInterviewAi.success(
                MAPPER.createObjectNode().put("card_id", 999_999).put("sentence", "다툼이 줄었다."));
        Function<JsonNode, FakeInterviewAi.Reply> blank = request -> polished(request, "   ");
        Function<JsonNode, FakeInterviewAi.Reply> tooLong = request -> polished(request, "가".repeat(1001));

        return Stream.of(
                Arguments.of(Named.of("AI 오류", failure)),
                Arguments.of(Named.of("다른 카드 id", otherCard)),
                Arguments.of(Named.of("빈 문장", blank)),
                Arguments.of(Named.of("1,001자 문장", tooLong)));

    }

    static Stream<Arguments> questionsNotToPolish() {

        return Stream.of(
                Arguments.of(Named.of("기술 카드", "UPDATE card SET card_type = 'TECH' WHERE id = ?")),
                Arguments.of(Named.of("수치 질문",
                        "UPDATE interview_turn SET star_slot = 'R', answer_mode = 'MEASURE' WHERE card_id = ?")));

    }

    static Stream<Arguments> invalidTexts() {

        return Stream.of(
                Arguments.of(Named.of("공백뿐", "   ")),
                Arguments.of(Named.of("1,001자", "가".repeat(1001))),
                Arguments.of(Named.of("U+0000", "회의\u0000시간")));

    }

    static Stream<Arguments> conflicts() {

        return Stream.of(
                Arguments.of(Named.of("넘긴 질문",
                        "UPDATE interview_turn SET outcome = 'SKIPPED', answered_at = now() WHERE card_id = ?"), "INVALID_STATE"),
                Arguments.of(Named.of("확정된 카드",
                        "UPDATE card SET status = 'CONFIRMED', confirmed_at = now() WHERE id = ?"), "CARD_CONFIRMED"));

    }

    private static FakeInterviewAi.Reply polished(JsonNode request, String sentence) {

        return FakeInterviewAi.success(MAPPER.createObjectNode().put("card_id", request.path("card_id").longValue())
                .put("sentence", sentence));

    }

    private Long cardWithOpenTurn() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", QUESTION);

        return cardId;

    }

    private ResultActions polish(String text) throws Exception {

        return mvc.perform(post(PATH, MY_CARD, 1).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("text", text)))
                .with(loggedIn()).with(csrf()));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
