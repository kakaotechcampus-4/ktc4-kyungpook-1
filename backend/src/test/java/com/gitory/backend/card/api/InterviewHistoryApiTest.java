package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class InterviewHistoryApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MY_OTHER_CARD = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");

    // FE schemas.ts 의 InterviewTurn 이 요구하는 키로, 값이 null 이어도 키는 빠지면 안 된다
    private static final String[] TURN_KEYS = {"turnNo", "field", "askedBy", "sourceType", "questionType", "found",
            "missing", "question", "options", "answer", "remaining", "maxTurns"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

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

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

    }

    @Test
    @DisplayName("로그인하지 않고 조회하면 401 UNAUTHENTICATED 이다")
    void requiresLogin() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        mvc.perform(get("/api/cards/{id}/interview", MY_CARD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 면 404 NOT_FOUND 이고 거절 기록은 남지 않는다")
    void unknownCardIsNotFound(String id) throws Exception {

        history(myUserId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("남의 카드는 질문이 있어도 없는 카드와 똑같이 404 NOT_FOUND 이고 그 카드로 VIEW_CARD 거절 기록이 하나 남는다")
    void deniesOthersCard() throws Exception {

        // 남의 카드 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        fixtures.insertInterviewTurn(othersCardId, 1, "T");

        history(myUserId, OTHERS_CARD.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_CARD", othersCardId, "DENIED"));

    }

    @Test
    @DisplayName("질문이 없는 내 카드는 200 과 빈 배열이고, 같은 사용자의 다른 카드에 질문이 있어도 섞이지 않는다")
    void returnsEmptyArrayForCardWithoutTurns() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);
        Long otherCardId = fixtures.insertCard(MY_OTHER_CARD, myUserId);
        fixtures.insertInterviewTurn(otherCardId, 1, "S");

        history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data", hasSize(0)));

    }

    @Test
    @DisplayName("카드의 질문만 나오고, 같은 사용자의 다른 카드가 가진 질문은 응답에 나타나지 않는다")
    void listsOnlyThisCardsTurns() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long otherCardId = fixtures.insertCard(MY_OTHER_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "이 카드의 질문");
        interviews.insertAsked(otherCardId, 1, "T", "EVIDENCE_GAP", "USER", "다른 카드의 첫 질문");
        interviews.insertAsked(otherCardId, 2, "A", "EVIDENCE_GAP", "USER", "다른 카드의 둘째 질문");

        history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].question").value("이 카드의 질문"));

    }

    @Test
    @DisplayName("질문은 질문 번호 순서로 나오고, 행을 2·3·1 번 순서로 넣어도 1·2·3 번으로 온다")
    void ordersTurnsBySeq() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 2, "T", "EVIDENCE_GAP", "USER", "둘째");
        interviews.insertAsked(cardId, 3, "A", "EVIDENCE_GAP", "USER", "셋째");
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "첫째");

        String body = history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(JsonPath.<List<Integer>>read(body, "$.data[*].turnNo")).containsExactly(1, 2, 3);
        assertThat(JsonPath.<List<String>>read(body, "$.data[*].question")).containsExactly("첫째", "둘째", "셋째");

    }

    @Test
    @DisplayName("질문 하나는 FE InterviewTurn 의 12개 키를 모두 담고, 질문 번호는 seq·질문 글은 저장된 그대로이며 출처는 DIRECT_CARD 이고 근거·보기는 빈 배열이다")
    void exposesTwelveKeysOfOneTurn() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        // 질문 번호가 행 id 와 같아 보이지 않도록 다른 카드의 행으로 id 를 하나 써 둔다
        fixtures.insertInterviewTurn(fixtures.insertCard(MY_OTHER_CARD, myUserId), 1, "S");
        interviews.insertAsked(cardId, 1, "A", "FOLLOWUP", "AUTO", "  이 작업에서 맡은 역할은 무엇이었나요?  ");

        String body = history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> turns = JsonPath.read(body, "$.data");
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0)).containsOnlyKeys(TURN_KEYS)
                .containsEntry("turnNo", 1)
                .containsEntry("field", "A")
                .containsEntry("askedBy", "FOLLOW_UP")
                .containsEntry("sourceType", "DIRECT_CARD")
                .containsEntry("questionType", "FOLLOWUP")
                .containsEntry("found", List.of())
                .containsEntry("missing", List.of())
                .containsEntry("question", "  이 작업에서 맡은 역할은 무엇이었나요?  ")
                .containsEntry("options", List.of())
                .containsEntry("answer", null)
                .containsEntry("remaining", 1)
                .containsEntry("maxTurns", 2);

    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"USER, USER_REQUEST", "AUTO, FOLLOW_UP"})
    @DisplayName("저장된 USER 는 USER_REQUEST, AUTO 는 FOLLOW_UP 으로 나온다")
    void mapsTriggerSourceToAskedBy(String stored, String expected) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "T", "EVIDENCE_GAP", stored, "질문");

        history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].askedBy").value(expected));

    }

    @ParameterizedTest
    @ValueSource(strings = {"EVIDENCE_GAP", "FOLLOWUP", "RECALL_AID"})
    @DisplayName("저장된 질문 종류는 바뀌지 않고 그대로 나온다")
    void keepsStoredQuestionType(String questionType) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "T", questionType, "USER", "질문");

        history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].questionType").value(questionType));

    }

    @ParameterizedTest
    @ValueSource(strings = {"S", "T", "A", "R"})
    @DisplayName("질문이 묻는 칸은 저장된 칸 그대로 나온다")
    void keepsStoredField(String slot) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, slot, "EVIDENCE_GAP", "USER", "질문");

        history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].field").value(slot));

    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"TYPED, USER_STATED", "SELECTED, USER_SELECTED"})
    @DisplayName("결과가 ANSWERED 인 질문은 답 글과 출처(TYPED 는 USER_STATED, SELECTED 는 USER_SELECTED)를 answer 로 담는다")
    void exposesAnswerOfAnsweredTurn(String stored, String expected) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAnswered(cardId, 1, "T", "  앞뒤 공백까지 쓴 답  ", stored);

        String body = history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> answer = JsonPath.read(body, "$.data[0].answer");
        assertThat(answer).containsOnlyKeys("text", "source")
                .containsEntry("text", "  앞뒤 공백까지 쓴 답  ")
                .containsEntry("source", expected);

    }

    @Test
    @DisplayName("답하지 않은 질문의 answer 는 null 이고 키는 빠지지 않는다")
    void keepsAnswerKeyForOpenTurn() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertInterviewTurn(cardId, 1, "T");

        String body = history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> turn = JsonPath.read(body, "$.data[0]");
        assertThat(turn).containsKey("answer").containsEntry("answer", null);

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"LATER", "SKIPPED", "INSUFFICIENT"})
    @DisplayName("답 글이 남아 있어도 결과가 ANSWERED 가 아니거나 비어 있으면 answer 는 null 이고 키는 빠지지 않는다")
    void hidesAnswerWhenOutcomeIsNotAnswered(String outcome) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "T", "EVIDENCE_GAP", "USER", "질문", outcome, "남은 답 글", "TYPED");

        String body = history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> turn = JsonPath.read(body, "$.data[0]");
        assertThat(turn).containsOnlyKeys(TURN_KEYS).containsEntry("answer", null);

    }

    @Test
    @DisplayName("남은 질문 수는 설정의 최대 질문 수 2 에서 질문 번호를 뺀 값이고, 최대를 넘는 번호의 질문은 0 이다")
    void computesRemainingFromMaxTurns() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "첫째");
        interviews.insertAsked(cardId, 2, "T", "EVIDENCE_GAP", "USER", "둘째");
        interviews.insertAsked(cardId, 3, "A", "EVIDENCE_GAP", "USER", "셋째");

        String body = history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(JsonPath.<List<Integer>>read(body, "$.data[*].remaining")).containsExactly(1, 0, 0);
        assertThat(JsonPath.<List<Integer>>read(body, "$.data[*].maxTurns")).containsExactly(2, 2, 2);

    }

    @Test
    @DisplayName("확정된 카드도 질문을 그대로 조회한다")
    void readsConfirmedCard() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(cardId);
        interviews.insertAnswered(cardId, 1, "T", "확정 전에 한 답", "TYPED");

        history(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].answer.text").value("확정 전에 한 답"));

    }

    @Test
    @DisplayName("작성 중인 카드든 확정된 카드든 읽는 데 성공하면 감사 기록은 하나도 남지 않는다")
    void successfulReadLeavesNoAuditRow() throws Exception {

        Long draftId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertInterviewTurn(draftId, 1, "T");
        Long confirmedId = fixtures.insertCard(MY_OTHER_CARD, myUserId);
        fixtures.insertCardVersion(confirmedId, 2, "USER_EDIT", true);
        fixtures.confirmCard(confirmedId);

        history(myUserId, MY_CARD.toString()).andExpect(status().isOk());
        history(myUserId, MY_OTHER_CARD.toString()).andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    private ResultActions history(Long userId, String id) throws Exception {

        return mvc.perform(get("/api/cards/{id}/interview", id).with(loggedInAs(userId)));

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
