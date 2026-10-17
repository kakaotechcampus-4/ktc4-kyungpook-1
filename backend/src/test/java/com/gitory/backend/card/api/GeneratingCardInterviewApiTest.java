package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.InterviewFixtures.TurnRow;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
class GeneratingCardInterviewApiTest {

    private static final FakeInterviewAi AI = FakeInterviewAi.start();
    private static final String ASK_PATH = "/api/cards/{id}/interview";
    private static final String ANSWER_PATH = "/api/cards/{id}/interview/{turnNo}/answer";
    private static final String ANSWER_BODY = "{\"text\":\"답\",\"source\":\"USER_STATED\"}";

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
    private CandidateCardFixtures cards;
    private Long myUserId;
    private Long myUserRepositoryId;
    private Long othersUserRepositoryId;
    private Long othersUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);
        cards = new CandidateCardFixtures(jdbc);
        fixtures.clear();
        AI.reset();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");
        myUserRepositoryId = fixtures.insertUserRepository(myUserId, fixtures.insertRepository(1L, "grow22", "gitory"));
        othersUserRepositoryId = fixtures.insertUserRepository(othersUserId, fixtures.insertRepository(2L, "someone-else", "other"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING"})
    @DisplayName("초안 Job 이 대기 중이거나 실행 중인 카드에 질문을 요청하면 409 INVALID_STATE 이고 AI 를 부르지 않으며 질문도 만들지 않는다")
    void askIsRefusedWhileDraftJobIsActive(String state) throws Exception {

        Long cardId = generatingCard(state);

        ask(cardPublicId(cardId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(AI.requests()).isEmpty();
        assertThat(interviews.turnsOf(cardId)).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELED"})
    @DisplayName("초안 Job 이 끝난 카드는 일부만 만들어졌어도 질문을 받을 수 있다")
    void askIsAllowedOnceDraftJobHasEnded(String state) throws Exception {

        Long cardId = generatingCard(state, true);

        ask(cardPublicId(cardId)).andExpect(status().isOk());

        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    @Test
    @DisplayName("초안을 만드는 중인 카드는 질문 상한에 닿았어도 INTERVIEW_CAP 이 아니라 INVALID_STATE 이다")
    void generatingBeatsInterviewCap() throws Exception {

        Long cardId = generatingCard("RUNNING");
        interviews.insertAnswered(cardId, 1, "S", "답", "TYPED");
        interviews.insertAnswered(cardId, 2, "T", "답", "TYPED");

        ask(cardPublicId(cardId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

    }

    @Test
    @DisplayName("초안을 만드는 중인 카드는 확정돼 있어도 CARD_CONFIRMED 가 아니라 INVALID_STATE 이다")
    void generatingBeatsConfirmedCard() throws Exception {

        Long cardId = generatingCard("RUNNING");
        jdbc.update("UPDATE card SET status = 'CONFIRMED', confirmed_at = now() WHERE id = ?", cardId);

        ask(cardPublicId(cardId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

    }

    @Test
    @DisplayName("남의 카드는 초안을 만드는 중이어도 409 가 아니라 없는 카드와 같은 404 이다")
    void othersGeneratingCardIsStillNotFound() throws Exception {

        UUID jobId = cards.insertDraftJob(othersUserId, othersUserRepositoryId, "RUNNING");
        Long cardId = cards.insertCandidateCard(othersUserId, othersUserRepositoryId, null, jobId, false);

        ask(cardPublicId(cardId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        answer(cardPublicId(cardId), 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING"})
    @DisplayName("초안 Job 이 대기 중이거나 실행 중인 카드의 질문에 답하면 409 INVALID_STATE 이고 새 버전도 답도 남기지 않는다")
    void answerIsRefusedWhileDraftJobIsActive(String state) throws Exception {

        Long cardId = generatingCard(state);
        interviews.insertAsked(cardId, 1, "T", "EVIDENCE_GAP", "USER", "과제는 무엇이었나요?");

        answer(cardPublicId(cardId), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::answerText).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_version WHERE card_id = ?", Integer.class, cardId))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("초안을 만드는 중인 카드는 이미 답한 질문이어도 초안 때문에 거절한다는 안내가 먼저 나온다")
    void generatingBeatsAnsweredTurn() throws Exception {

        Long cardId = generatingCard("RUNNING");
        interviews.insertAnswered(cardId, 1, "T", "답", "TYPED");

        answer(cardPublicId(cardId), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("초안")));

    }

    @Test
    @DisplayName("없는 질문 번호는 초안을 만드는 중인 카드에서도 409 가 아니라 404 이다")
    void unknownTurnIsNotFoundBeforeGeneratingCheck() throws Exception {

        Long cardId = generatingCard("RUNNING");

        answer(cardPublicId(cardId), 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELED"})
    @DisplayName("초안 Job 이 끝난 카드의 질문에는 답할 수 있다")
    void answerIsAllowedOnceDraftJobHasEnded(String state) throws Exception {

        Long cardId = generatingCard(state);
        interviews.insertAsked(cardId, 1, "T", "EVIDENCE_GAP", "USER", "과제는 무엇이었나요?");

        answer(cardPublicId(cardId), 1).andExpect(status().isOk());

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::answerText).containsExactly("답");

    }

    private Long generatingCard(String jobState) {

        return generatingCard(jobState, false);

    }

    private Long generatingCard(String jobState, boolean generationPartial) {

        UUID jobId = cards.insertDraftJob(myUserId, myUserRepositoryId, jobState);

        return cards.insertCandidateCard(myUserId, myUserRepositoryId, null, jobId, generationPartial);

    }

    private UUID cardPublicId(Long cardId) {

        return cards.cardPublicIdOf(cardId);

    }

    private ResultActions ask(UUID cardId) throws Exception {

        return mvc.perform(post(ASK_PATH, cardId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"T\"}").with(loggedInAs(myUserId)).with(csrf()));

    }

    private ResultActions answer(UUID cardId, int turnNo) throws Exception {

        return mvc.perform(post(ANSWER_PATH, cardId, turnNo).contentType(MediaType.APPLICATION_JSON)
                .content(ANSWER_BODY).with(loggedInAs(myUserId)).with(csrf()));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
