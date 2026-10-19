package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.InterviewFixtures.TurnRow;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 답 평가 스위치를 켠 채 답 저장 API 를 실제 DB 와 가짜 AI 로 끝까지 확인한다 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b",
        "gitory.interview.answer-review.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
class InterviewAnswerReviewApiTest {

    private static final FakeInterviewAi AI = FakeInterviewAi.start();
    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");

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
    @DisplayName("답이 모자라다는 평가를 받으면 답 저장 응답은 200 과 저장된 카드 그대로이고, 같은 칸의 후속 질문이 AUTO 로 DB 에 남는다")
    void shortAnswerCreatesFollowUpAfterTheAnswerIsSaved() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long firstTurnId = interviews.insertAsked(cardId, 1, "A", "EVIDENCE_GAP", "USER", "무엇을 했나요?");
        AI.replyWith(request -> insufficient("A", "어떤 방법으로 고쳤나요?"));

        String answered = mvc.perform(post("/api/cards/{id}/interview/{turnNo}/answer", MY_CARD, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"고쳤다\",\"source\":\"USER_STATED\"}")
                        .with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String saved = mvc.perform(get("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(JsonMapper.builder().build().readTree(answered)).isEqualTo(JsonMapper.builder().build().readTree(saved));
        assertThat(AI.requests()).hasSize(1);
        assertThat(AI.requests().get(0).path("answer_text").asString()).isEqualTo("고쳤다");
        List<TurnRow> rows = interviews.turnsOf(cardId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).outcome()).isEqualTo("ANSWERED");
        assertThat(rows.get(0).answerText()).isEqualTo("고쳤다");
        TurnRow followUp = rows.get(1);
        assertThat(followUp.seq()).isEqualTo(2);
        assertThat(followUp.starSlot()).isEqualTo("A");
        assertThat(followUp.questionType()).isEqualTo("FOLLOWUP");
        assertThat(followUp.triggerSource()).isEqualTo("AUTO");
        assertThat(followUp.parentTurnId()).isEqualTo(firstTurnId);
        assertThat(followUp.questionText()).isEqualTo("어떤 방법으로 고쳤나요?");
        assertThat(followUp.outcome()).isNull();

    }

    private static FakeInterviewAi.Reply insufficient(String slot, String questionText) {

        ObjectNode target = JsonMapper.builder().build().createObjectNode().put("star_slot", slot)
                .put("statement_seq", 1);
        ObjectNode nextTurn = JsonMapper.builder().build().createObjectNode();
        nextTurn.set("target", target);
        nextTurn.put("question_type", "FOLLOWUP").put("trigger_source", "AUTO").put("question_text", questionText);
        ObjectNode data = JsonMapper.builder().build().createObjectNode().put("outcome", "INSUFFICIENT")
                .put("next_action", "ASK_AGAIN");
        data.putNull("resulting_statement");
        data.putArray("remaining_slots");
        data.set("next_turn", nextTurn);

        return FakeInterviewAi.success(data);

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
