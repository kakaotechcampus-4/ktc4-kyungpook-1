package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.InterviewFixtures.TurnRow;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class InterviewAnswerApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String ANSWER_PATH = "/api/cards/{id}/interview/{turnNo}/answer";
    private static final String USER_STATED = "USER_STATED";
    private static final String LIMIT_TEXT = "가".repeat(1000);
    private static final String OVER_LIMIT_TEXT = "가".repeat(1001);
    // 😀 하나가 UTF-16 두 칸이라 String.length() 는 1,001 이지만 코드 포인트는 1,000개다
    private static final String OVER_LIMIT_BY_UTF16 = "a".repeat(999) + "😀";
    // 😀 500개는 코드 포인트가 500개지만 String.length() 가 정확히 1,000 이다
    private static final String LIMIT_BY_UTF16 = "😀".repeat(500);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TestFixtures fixtures;
    private InterviewFixtures interviews;
    private Long myUserId;
    private Long othersUserId;
    private Long firstCommit;
    private Long secondCommit;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        interviews = new InterviewFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        firstCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        secondCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @Test
    @DisplayName("로그인하지 않으면 401 UNAUTHENTICATED, CSRF 토큰이 없으면 403 FORBIDDEN 이고 질문과 카드는 그대로다")
    void requiresSessionAndCsrf() throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        mvc.perform(answerRequest(MY_CARD.toString(), "1", body("답", USER_STATED)).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(answerRequest(MY_CARD.toString(), "1", body("답", USER_STATED)).with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(events()).isEmpty();

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 면 404 NOT_FOUND 이고 거절 기록을 남기지 않는다")
    void unknownCardIsNotFoundWithoutRecord(String id) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, id, "1", body("답", USER_STATED))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();
        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @MethodSource("bodiesThatAreValidOrDoublyInvalid")
    @DisplayName("남의 카드는 답이 맞든 틀리든 없는 카드와 똑같이 404 이고 EDIT_CARD 거절 기록이 그 카드 내부 id 로 하나만 남으며 질문과 카드는 그대로다")
    void othersCardIsNotFoundAndRecorded(String body) throws Exception {

        Long othersCardId = othersCardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(othersCardId);

        send(myUserId, OTHERS_CARD.toString(), "1", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "EDIT_CARD", othersCardId, "DENIED"));
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999"})
    @DisplayName("질문 번호가 정수가 아니면 남의 카드여도 400 INVALID_REQUEST 이고 거절 기록을 남기지 않는다")
    void nonIntegerTurnNoIsBadRequestBeforeAnyCardCheck(String turnNo) throws Exception {

        Long othersCardId = othersCardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(othersCardId);

        send(myUserId, OTHERS_CARD.toString(), turnNo, body("답", USER_STATED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(events()).isEmpty();
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 32768, 65537, -65535, Integer.MAX_VALUE})
    @DisplayName("SMALLINT 범위 밖 질문 번호는 short 로 줄어 다른 질문을 가리키지 않고 404 이며 1번 질문은 답하지 않은 채 그대로다")
    void turnNoOutsideSmallintIsNotFound(int turnNo) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), String.valueOf(turnNo), body("답", USER_STATED))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome).containsOnlyNulls();
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("SMALLINT 의 가장 큰 질문 번호 32767 은 범위 안이라 그 번호의 질문에 답할 수 있다")
    void largestSmallintTurnNoIsInRange() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 32767, "T", "EVIDENCE_GAP", "USER", "마지막 번호의 질문");

        send(myUserId, MY_CARD.toString(), "32767", body("답", USER_STATED)).andExpect(status().isOk());

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome).containsExactly("ANSWERED");

    }

    @Test
    @DisplayName("그 카드에 없는 번호의 질문은 404 이고 같은 사용자의 다른 카드에만 있는 번호여도 그 카드의 질문을 건드리지 않는다")
    void turnOfAnotherCardIsNotFound() throws Exception {

        Long cardId = cardWithOpenTurn();
        Long anotherCardId = fixtures.insertCard(UUID.randomUUID(), myUserId);
        interviews.insertAsked(anotherCardId, 2, "S", "EVIDENCE_GAP", "USER", "다른 카드의 질문");
        List<Map<String, Object>> before = stateOf(cardId);
        List<Map<String, Object>> anotherBefore = stateOf(anotherCardId);

        for (String turnNo : List.of("2", "3")) {
            send(myUserId, MY_CARD.toString(), turnNo, body("답", USER_STATED))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(stateOf(anotherCardId)).isEqualTo(anotherBefore);
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("질문 번호가 없으면 확정된 카드여도 409 CARD_CONFIRMED 가 아니라 404 이다")
    void missingTurnOnConfirmedCardIsNotFound() throws Exception {

        Long cardId = confirmedCardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "3", body("답", USER_STATED))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @MethodSource("bodiesThatAreValidOrDoublyInvalid")
    @DisplayName("확정된 카드에 답하지 않은 질문이 있으면 답이 맞든 틀리든 409 CARD_CONFIRMED 이고 버전·문장·질문·감사 기록이 그대로다")
    void confirmedCardIsConflictBeforeTheAnswerIsJudged(String body) throws Exception {

        Long cardId = confirmedCardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("CARD_CONFIRMED"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("확정된 카드에서 이미 한 답과 다른 답이 오면 409 CARD_CONFIRMED 가 아니라 409 INVALID_STATE 이다")
    void differentAnswerOnConfirmedCardIsInvalidState() throws Exception {

        Long cardId = confirmedCardWithOpenTurn();
        interviews.insertTurn(cardId, 2, "A", "EVIDENCE_GAP", "USER", "질문", "ANSWERED", "저장된 답", "TYPED");
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "2", body("다른 답", USER_STATED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("같은 답을 확정된 뒤에 다시 보내도 200 으로 지금 카드를 돌려주고 아무것도 바뀌지 않는다")
    void sameAnswerResentAfterConfirmationIsOk() throws Exception {

        Long cardId = cardWithOpenTurn();
        answer(MY_CARD, 1, "확정 전에 한 답", USER_STATED).andExpect(status().isOk());
        fixtures.confirmCard(cardId);
        List<Map<String, Object>> before = stateOf(cardId);

        answer(MY_CARD, 1, "확정 전에 한 답", USER_STATED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.version.task").value("확정 전에 한 답"))
                .andExpect(jsonPath("$.data.interviewTurns").value(1));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("같은 답이 다시 오면 200 으로 지금 카드를 돌려주고 새 버전·문장·카드와 질문의 시각이 하나도 바뀌지 않으며 감사 기록도 남지 않는다")
    void sameAnswerResentChangesNothing() throws Exception {

        Long cardId = cardWithOpenTurn();
        String first = bodyOf(answer(MY_CARD, 1, "  한 번 저장한 답  ", USER_STATED).andExpect(status().isOk()));
        List<Map<String, Object>> before = stateOf(cardId);

        String resent = bodyOf(answer(MY_CARD, 1, "  한 번 저장한 답  ", USER_STATED).andExpect(status().isOk()));

        assertThat(dataOf(resent)).isEqualTo(dataOf(first));
        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(versions(cardId)).extracting(VersionRow::versionNo).containsExactly(1, 2);
        assertThat(events()).isEmpty();

    }

    @ParameterizedTest
    @MethodSource("answersDifferentFromTheSavedOne")
    @DisplayName("이미 답한 질문에 저장된 답과 글이나 출처가 조금이라도 다른 요청이 오면 모양이 틀려도 400 이 아니라 409 INVALID_STATE 이고 아무것도 바뀌지 않는다")
    void differentAnswerToAnsweredTurnIsInvalidState(String text, String source) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "T", "EVIDENCE_GAP", "USER", "질문", "ANSWERED", "저장된 답", "TYPED");
        List<Map<String, Object>> before = stateOf(cardId);

        answer(MY_CARD, 1, text, source)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("저장된 답과의 비교는 글자 그대로라서 대소문자만 달라도 다른 답이고 같은 글자면 같은 답이다")
    void answerComparisonIsExactIncludingCase() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "T", "EVIDENCE_GAP", "USER", "질문", "ANSWERED", "Cache Hit Rate", "TYPED");
        List<Map<String, Object>> before = stateOf(cardId);

        answer(MY_CARD, 1, "cache hit rate", USER_STATED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        answer(MY_CARD, 1, "Cache Hit Rate", USER_STATED).andExpect(status().isOk());

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("저장된 답의 출처는 화면이 쓰는 이름으로 비교해, 보기에서 고른 답은 USER_SELECTED 로 다시 보낼 때만 같은 답이고 USER_STATED 로 보내면 다른 답이다")
    void savedSourceIsComparedByTheNameTheScreenUses() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "T", "EVIDENCE_GAP", "USER", "질문", "ANSWERED", "고른 답", "SELECTED");
        List<Map<String, Object>> before = stateOf(cardId);

        answer(MY_CARD, 1, "고른 답", "USER_SELECTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewTurns").value(1));
        assertThat(stateOf(cardId)).isEqualTo(before);

        answer(MY_CARD, 1, "고른 답", USER_STATED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "TYPED", "SELECTED", "user_stated", " USER_STATED", "USER_SELECTED ", ""})
    @DisplayName("source 가 정확히 USER_STATED·USER_SELECTED 가 아니면 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void onlyUserStatedSourceIsAccepted(String source) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        answer(MY_CARD, 1, "답", source)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);
        assertThat(events()).isEmpty();

    }

    @ParameterizedTest
    @MethodSource("bodiesWithoutUsableSource")
    @DisplayName("source 가 null 이거나 빠진 본문은 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void missingOrNullSourceIsBadRequest(String body) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @MethodSource("bodiesWithoutText")
    @DisplayName("text 가 null 이거나 빠진 본문은 500 이 아니라 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void missingOrNullTextIsBadRequest(String body) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @MethodSource("blankTexts")
    @DisplayName("공백 문자뿐인 답은 NBSP·BOM 이 섞여도 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void blankTextIsBadRequest(String text) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        answer(MY_CARD, 1, text, USER_STATED)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "앞\u0000뒤", "끝에 붙은 NUL\u0000"})
    @DisplayName("답에 U+0000 이 하나라도 있으면 500 이 아니라 400 INVALID_REQUEST 이고, 새 버전을 만들기 전에 답을 검사해 거절하며 질문과 카드는 그대로다")
    void nulCharacterIsBadRequest(String text) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        // 문장을 만들 때의 검사가 같은 400 을 한 번 더 내므로, 답 검사의 메시지로 어디서 거절했는지 가른다
        answer(MY_CARD, 1, text, USER_STATED)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(containsString("답에 쓸 수 없는 글자")));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @ParameterizedTest
    @MethodSource("textsOfExactlyTheLimit")
    @DisplayName("String.length() 가 정확히 1,000 인 답은 받아서 그대로 저장한다")
    void acceptsAnswerOfExactlyTheLimit(String text) throws Exception {

        Long cardId = cardWithOpenTurn();

        answer(MY_CARD, 1, text, USER_STATED).andExpect(status().isOk());

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::answerText).containsExactly(text);
        assertThat(slotsOf(cardId, 2).get("T").body()).isEqualTo(text);

    }

    @ParameterizedTest
    @MethodSource("textsOverTheLimit")
    @DisplayName("String.length() 가 1,001 인 답은 코드 포인트로는 1,000개여도 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void rejectsAnswerOverTheLimit(String text) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        answer(MY_CARD, 1, text, USER_STATED)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("답의 앞뒤 공백과 줄바꿈은 자르지 않고 질문·문장·응답에 그대로 담는다")
    void keepsSurroundingWhitespaceExactly() throws Exception {

        Long cardId = cardWithOpenTurn();
        String text = "  앞뒤 공백과\n줄바꿈이 있는 답  \n";

        answer(MY_CARD, 1, text, USER_STATED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.task").value(text));

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::answerText).containsExactly(text);
        assertThat(slotsOf(cardId, 2).get("T").body()).isEqualTo(text);

    }

    @ParameterizedTest
    @MethodSource("unbindableBodies")
    @DisplayName("JSON 객체가 아니거나 글·출처가 문자열이 아니라 읽을 수 없는 본문은 남의 카드여도 400 INVALID_REQUEST 이고 거절 기록을 남기지 않는다")
    void unbindableBodyIsBadRequestBeforeAnyCardCheck(String body) throws Exception {

        Long othersCardId = othersCardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(othersCardId);

        send(myUserId, OTHERS_CARD.toString(), "1", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(events()).isEmpty();
        assertThat(stateOf(othersCardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("성공해도 감사 기록은 하나도 남지 않는다")
    void successLeavesNoAuditRow() throws Exception {

        cardWithOpenTurn();

        answer(MY_CARD, 1, "답", USER_STATED).andExpect(status().isOk());

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("답한 질문은 글을 그대로 담고 출처 TYPED·결과 ANSWERED·답한 시각이 채워지며 질문 쪽 칸은 하나도 바뀌지 않는다")
    void savesTheAnswerIntoTheTurnRow() throws Exception {

        Long cardId = cardWithOpenTurn();
        TurnRow asked = interviews.turnsOf(cardId).get(0);
        Instant started = Instant.now();

        answer(MY_CARD, 1, "과제는 로그인 지연을 줄이는 것이었다", USER_STATED).andExpect(status().isOk());

        Instant finished = Instant.now();
        TurnRow saved = interviews.turnsOf(cardId).get(0);
        assertThat(saved.answerText()).isEqualTo("과제는 로그인 지연을 줄이는 것이었다");
        assertThat(saved.answerSource()).isEqualTo("TYPED");
        assertThat(saved.outcome()).isEqualTo("ANSWERED");
        assertThat(saved.answeredAt()).isBetween(started.truncatedTo(ChronoUnit.MICROS), finished);
        assertThat(saved).usingRecursiveComparison()
                .ignoringFields("answerText", "answerSource", "outcome", "answeredAt")
                .isEqualTo(asked);

    }

    @Test
    @DisplayName("답하면 INTERVIEW 새 버전이 현재 버전이 되고, 답한 칸만 질문에 연결된 사용자 문장(근거 커밋·확신도 없음)으로 바뀌며 나머지 세 칸은 근거째 그대로다")
    void createsInterviewVersionAndChangesOnlyTheAnsweredSlot() throws Exception {

        Rich rich = richCard();
        Map<String, StatementRow> versionOne = slotsOf(rich.cardId(), 1);
        // DB 시계와 테스트 시계가 어긋날 수 있어, 만든 시각이 아니라 한참 전 시각에서 비교한다
        setUpdatedAt(rich.cardId(), "2026-01-01T00:00:00Z");
        Instant cardUpdatedBefore = cardUpdatedAt(rich.cardId());

        answer(MY_CARD, 2, "새로 쓴 행동", USER_STATED).andExpect(status().isOk());

        assertThat(versions(rich.cardId())).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "INTERVIEW", false));
        assertThat(currentVersion(rich.cardId())).isEqualTo(2);
        assertThat(slotsOf(rich.cardId(), 1)).isEqualTo(versionOne);
        Map<String, StatementRow> versionTwo = slotsOf(rich.cardId(), 2);
        assertThat(versionTwo).containsOnlyKeys("S", "T", "A", "R");
        assertThat(versionTwo.get("S")).isEqualTo(
                new StatementRow("커밋으로 쓴 상황", "COMMIT", "MEDIUM", null, List.of(firstCommit, secondCommit)));
        assertThat(versionTwo.get("T")).isEqualTo(
                new StatementRow("옛 답", "USER_STATED", null, rich.answeredTurnId(), List.of()));
        assertThat(versionTwo.get("A")).isEqualTo(
                new StatementRow("새로 쓴 행동", "USER_STATED", null, rich.openTurnId(), List.of()));
        assertThat(versionTwo.get("R")).isEqualTo(
                new StatementRow("흐린 결과", "COMMIT", "LOW", null, List.of(secondCommit)));
        assertThat(cardUpdatedAt(rich.cardId())).isAfter(cardUpdatedBefore);

    }

    @Test
    @DisplayName("답한 칸은 옛 글의 확인 필요 표시와 커밋 근거를 잃고 질문 번호가 붙은 사용자 근거 하나가 되며, 다른 칸의 확인 필요 표시와 근거는 남고, 답한 질문은 모두 센다")
    void answeredSlotLosesReviewMarkAndCommitEvidence() throws Exception {

        richCard();

        String body = bodyOf(answer(MY_CARD, 2, "새로 쓴 행동", USER_STATED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.version.source").value("INTERVIEW"))
                .andExpect(jsonPath("$.data.version.situation").value("커밋으로 쓴 상황"))
                .andExpect(jsonPath("$.data.version.task").value("옛 답"))
                .andExpect(jsonPath("$.data.version.action").value("새로 쓴 행동"))
                .andExpect(jsonPath("$.data.version.result").value("흐린 결과"))
                .andExpect(jsonPath("$.data.lowConfidenceFields", hasSize(1)))
                .andExpect(jsonPath("$.data.lowConfidenceFields[0].field").value("R"))
                .andExpect(jsonPath("$.data.interviewTurns").value(2)));

        List<Map<String, Object>> evidence = JsonPath.read(body, "$.data.evidence");
        assertThat(evidence).extracting(row -> row.get("field"), row -> row.get("type"), row -> row.get("authoredBy"),
                        row -> row.get("turnNo"))
                .containsExactly(
                        tuple("S", "COMMIT", "AI", null),
                        tuple("S", "COMMIT", "AI", null),
                        tuple("T", "USER_STATED", "USER", 1),
                        tuple("A", "USER_STATED", "USER", 2),
                        tuple("R", "COMMIT", "AI", null));

    }

    @ParameterizedTest
    @ValueSource(strings = {"LATER", "INSUFFICIENT"})
    @DisplayName("LATER·INSUFFICIENT 로 남아 답 글이 있는 질문은 아직 열린 질문이라서 다른 글로 답하면 200 으로 새 답이 된다")
    void turnThatEndedWithoutAnswerCanBeAnswered(String outcome) throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "T", "EVIDENCE_GAP", "USER", "질문", outcome, "남아 있던 글", "SELECTED");

        answer(MY_CARD, 1, "새 답", USER_STATED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.task").value("새 답"))
                .andExpect(jsonPath("$.data.interviewTurns").value(1));

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText, TurnRow::answerSource)
                .containsExactly(tuple("ANSWERED", "새 답", "TYPED"));
        assertThat(versions(cardId)).extracting(VersionRow::source).containsExactly("USER_EDIT", "INTERVIEW");

    }

    @Test
    @DisplayName("비어 있던 칸에 답하면 그 칸만 문장이 생기고 나머지 빈 칸은 문장 행을 만들지 않는다")
    void answeringAnEmptySlotFillsOnlyThatSlot() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long turnId = interviews.insertAsked(cardId, 1, "R", "EVIDENCE_GAP", "USER", "결과는 어땠나요?");

        answer(MY_CARD, 1, "응답 속도가 절반이 됐다", USER_STATED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.situation").value(nullValue()))
                .andExpect(jsonPath("$.data.version.result").value("응답 속도가 절반이 됐다"));

        assertThat(slotsOf(cardId, 1)).isEmpty();
        assertThat(slotsOf(cardId, 2)).containsOnlyKeys("R");
        assertThat(slotsOf(cardId, 2).get("R"))
                .isEqualTo(new StatementRow("응답 속도가 절반이 됐다", "USER_STATED", null, turnId, List.of()));

    }

    @Test
    @DisplayName("같은 칸을 두 번째 질문으로 다시 답하면 그 칸 문장은 하나뿐이고 새 답이 앞 답을 대신하며 앞 버전에는 앞 답이 그대로 남는다")
    void secondAnswerReplacesTheFirstAnswerOfTheSameSlot() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long firstTurn = interviews.insertAnswered(cardId, 1, "T", "첫 답", "TYPED");
        fixtures.insertStatement(cardId, 1, "T", "첫 답", "USER_STATED", null, firstTurn);
        Long secondTurn = interviews.insertAsked(cardId, 2, "T", "FOLLOWUP", "USER", "더 자세히 알려 주세요");

        answer(MY_CARD, 2, "둘째 답", USER_STATED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.task").value("둘째 답"))
                .andExpect(jsonPath("$.data.interviewTurns").value(2))
                .andExpect(jsonPath("$.data.evidence", hasSize(1)))
                .andExpect(jsonPath("$.data.evidence[0].turnNo").value(2));

        assertThat(statementIds(cardId, 2)).hasSize(1);
        assertThat(slotsOf(cardId, 2).get("T"))
                .isEqualTo(new StatementRow("둘째 답", "USER_STATED", null, secondTurn, List.of()));
        assertThat(slotsOf(cardId, 1).get("T"))
                .isEqualTo(new StatementRow("첫 답", "USER_STATED", null, firstTurn, List.of()));

    }

    @Test
    @DisplayName("답한 질문 수는 답하지 않은 질문을 세지 않고, 답 응답은 이어서 읽은 카드 상세와 질문 번호·질문 수까지 똑같다")
    void responseEqualsTheFollowingDetail() throws Exception {

        richCard();

        String answered = bodyOf(answer(MY_CARD, 2, "새로 쓴 행동", USER_STATED).andExpect(status().isOk()));
        String detail = bodyOf(mvc.perform(get("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk()));

        assertThat(dataOf(answered)).isEqualTo(dataOf(detail));
        assertThat(JsonPath.<Integer>read(detail, "$.data.interviewTurns")).isEqualTo(2);

    }

    @Test
    @DisplayName("답하지 않은 질문이 하나 더 남아 있으면 답한 질문 수만 센다")
    void countsOnlyAnsweredTurns() throws Exception {

        Long cardId = cardWithOpenTurn();
        interviews.insertAsked(cardId, 2, "A", "EVIDENCE_GAP", "USER", "아직 답하지 않은 질문");

        answer(MY_CARD, 1, "과제에 답했다", USER_STATED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewTurns").value(1));

        mvc.perform(get("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data.interviewTurns").value(1));

    }

    @Test
    @DisplayName("답한 칸의 글을 그대로 둔 채 다른 칸만 임시 저장하면 질문 번호가 남고, 그 칸의 글을 고치면 null 이 되지만 답한 질문 수는 그대로다")
    void turnNumberFollowsTheAnsweredText() throws Exception {

        cardWithOpenTurn();
        answer(MY_CARD, 1, "과제에 대한 답", USER_STATED).andExpect(status().isOk());

        saveDraft("새 상황", "과제에 대한 답", null, null).andExpect(status().isOk());
        String keptText = bodyOf(mvc.perform(get("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk()));
        List<Map<String, Object>> keptEvidence = JsonPath.read(keptText, "$.data.evidence");
        assertThat(keptEvidence).extracting(row -> row.get("field"), row -> row.get("turnNo"))
                .containsExactly(tuple("S", null), tuple("T", 1));
        assertThat(JsonPath.<Integer>read(keptText, "$.data.interviewTurns")).isEqualTo(1);

        saveDraft("새 상황", "고친 과제", null, null).andExpect(status().isOk());
        String editedText = bodyOf(mvc.perform(get("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk()));
        List<Map<String, Object>> editedEvidence = JsonPath.read(editedText, "$.data.evidence");
        assertThat(editedEvidence).extracting(row -> row.get("field"), row -> row.get("turnNo"))
                .containsExactly(tuple("S", null), tuple("T", null));
        assertThat(JsonPath.<Integer>read(editedText, "$.data.interviewTurns")).isEqualTo(1);

    }

    @Test
    @DisplayName("되묻기 기록은 답한 질문에 글과 USER_STATED 출처를 보여 주고 답하지 않은 질문의 답은 null 이다")
    void historyShowsTheAnswer() throws Exception {

        Long cardId = cardWithOpenTurn();
        interviews.insertAsked(cardId, 2, "A", "EVIDENCE_GAP", "USER", "행동은 무엇이었나요?");

        answer(MY_CARD, 1, "과제에 대한 답", USER_STATED).andExpect(status().isOk());

        mvc.perform(get("/api/cards/{id}/interview", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].turnNo").value(1))
                .andExpect(jsonPath("$.data[0].answer.text").value("과제에 대한 답"))
                .andExpect(jsonPath("$.data[0].answer.source").value(USER_STATED))
                .andExpect(jsonPath("$.data[1].turnNo").value(2))
                .andExpect(jsonPath("$.data[1].answer").value(nullValue()));

    }

    @Test
    @DisplayName("답한 카드는 목록 맨 앞으로 오고 출처 이름이 INTERVIEW 가 되며, 답하지 않은 질문만 있는 카드는 MANUAL 그대로다")
    void answeredCardMovesToTheFrontWithInterviewLabel() throws Exception {

        Long older = cardWithOpenTurn();
        setUpdatedAt(older, "2026-01-01T00:00:00Z");
        UUID newerId = UUID.randomUUID();
        Long newer = fixtures.insertCard(newerId, myUserId);
        interviews.insertAsked(newer, 1, "S", "EVIDENCE_GAP", "USER", "열려 있는 질문");
        setUpdatedAt(newer, "2026-02-01T00:00:00Z");

        mvc.perform(get("/api/cards").with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data[0].id").value(newerId.toString()))
                .andExpect(jsonPath("$.data[0].sourceLabel").value("MANUAL"))
                .andExpect(jsonPath("$.data[1].sourceLabel").value("MANUAL"));

        answer(MY_CARD, 1, "과제에 대한 답", USER_STATED).andExpect(status().isOk());

        mvc.perform(get("/api/cards").with(loggedInAs(myUserId)))
                .andExpect(jsonPath("$.data[0].id").value(MY_CARD.toString()))
                .andExpect(jsonPath("$.data[0].sourceLabel").value("INTERVIEW"))
                .andExpect(jsonPath("$.data[0].versionNo").value(2))
                .andExpect(jsonPath("$.data[0].userStatedCount").value(1))
                .andExpect(jsonPath("$.data[1].id").value(newerId.toString()))
                .andExpect(jsonPath("$.data[1].sourceLabel").value("MANUAL"));

    }

    @Test
    @DisplayName("같은 답 두 건이 동시에 오면 카드 행 잠금으로 차례로 처리돼 둘 다 200 이고 새 버전은 하나만 생긴다")
    void concurrentSameAnswersCreateOneVersion() throws Exception {

        Long cardId = cardWithOpenTurn();

        List<MvcResult> responses = answerTwiceAtOnce(cardId, "같은 답", "같은 답");

        assertThat(responses).extracting(response -> response.getResponse().getStatus()).containsExactly(200, 200);
        for (MvcResult response : responses) {
            assertThat(JsonPath.<Integer>read(contentOf(response), "$.data.version.versionNo")).isEqualTo(2);
        }
        assertThat(versions(cardId)).containsExactly(
                new VersionRow(1, "USER_EDIT", false),
                new VersionRow(2, "INTERVIEW", false));
        assertThat(slotsOf(cardId, 2)).containsOnlyKeys("T");
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::answerText).containsExactly("같은 답");

    }

    @Test
    @DisplayName("다른 답 두 건이 동시에 오면 먼저 잠금을 얻은 쪽만 저장되고 나머지는 409 INVALID_STATE 이며 새 버전은 하나만 생긴다")
    void concurrentDifferentAnswersLeaveOneWinner() throws Exception {

        Long cardId = cardWithOpenTurn();

        List<MvcResult> responses = answerTwiceAtOnce(cardId, "첫째 답", "둘째 답");

        assertThat(responses).extracting(response -> response.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult winner = responses.stream().filter(response -> response.getResponse().getStatus() == 200)
                .findFirst().orElseThrow();
        MvcResult loser = responses.stream().filter(response -> response.getResponse().getStatus() == 409)
                .findFirst().orElseThrow();
        assertThat(JsonPath.<String>read(contentOf(loser), "$.error.code")).isEqualTo("INVALID_STATE");
        String winningText = JsonPath.read(contentOf(winner), "$.data.version.task");
        assertThat(versions(cardId)).extracting(VersionRow::versionNo).containsExactly(1, 2);
        assertThat(slotsOf(cardId, 2).get("T").body()).isEqualTo(winningText);
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::answerText).containsExactly(winningText);

    }

    @Test
    @DisplayName("OpenAPI 문서는 답변 저장의 200·400·401·403·404·409 응답을 우리가 쓴 설명으로 적고, 본문 칸은 kind 에 따라 쓰임이 달라 필수로 두지 않으며 로그인 사용자는 요청 변수로 보이지 않는다")
    void documentsEveryStatus() throws Exception {

        // 오류 처리기의 상태 코드는 springdoc 이 설명 없이도 응답으로 올려서, 응답이 있는지가 아니라 우리가 쓴 설명이 실렸는지를 본다
        String post = "$.paths['/api/cards/{id}/interview/{turnNo}/answer'].post";
        mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(post + ".summary").value("되묻기 답변 저장"))
                .andExpect(jsonPath(post + ".parameters.length()").value(2))
                .andExpect(jsonPath(post + ".parameters[0].schema.format").value("uuid"))
                .andExpect(jsonPath(post + ".requestBody").exists())
                .andExpect(jsonPath(post + ".responses['200'].description").value(containsString("같은 답이 다시 왔으면")))
                .andExpect(jsonPath(post + ".responses['400'].description").value(containsString("INVALID_REQUEST")))
                .andExpect(jsonPath(post + ".responses['401'].description").value(containsString("로그인 세션 없음")))
                .andExpect(jsonPath(post + ".responses['403'].description").value(containsString("CSRF")))
                .andExpect(jsonPath(post + ".responses['404'].description").value(containsString("그 번호의 질문 없음")))
                .andExpect(jsonPath(post + ".responses['409'].description").value(containsString("CARD_CONFIRMED")))
                .andExpect(jsonPath(post + ".responses['409'].description").value(containsString("INVALID_STATE")))
                .andExpect(jsonPath("$.components.schemas.InterviewAnswerRequest.required").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.InterviewAnswerRequest.properties.kind.enum",
                        containsInAnyOrder("TEXT", "MEASURED", "NOT_MEASURED", "SKIP")))
                .andExpect(jsonPath("$.components.schemas.InterviewAnswerRequest.properties.source.enum[0]")
                        .value(USER_STATED));

    }

    static Stream<Named<String>> bodiesThatAreValidOrDoublyInvalid() {

        return Stream.of(
                Named.of("맞는 답", "{\"text\":\"답\",\"source\":\"USER_STATED\"}"),
                Named.of("공백뿐인 글과 USER_SELECTED", "{\"text\":\" \",\"source\":\"USER_SELECTED\"}"),
                Named.of("null 글과 null 출처", "{\"text\":null,\"source\":null}"));

    }

    static Stream<Named<String>> bodiesWithoutUsableSource() {

        return Stream.of(
                Named.of("source 가 null", "{\"text\":\"답\",\"source\":null}"),
                Named.of("source 키 없음", "{\"text\":\"답\"}"));

    }

    static Stream<Named<String>> bodiesWithoutText() {

        return Stream.of(
                Named.of("text 가 null", "{\"text\":null,\"source\":\"USER_STATED\"}"),
                Named.of("text 키 없음", "{\"source\":\"USER_STATED\"}"),
                Named.of("빈 객체", "{}"));

    }

    static Stream<Named<String>> textsOfExactlyTheLimit() {

        return Stream.of(
                Named.of("한글 1,000자", LIMIT_TEXT),
                Named.of("😀 500개", LIMIT_BY_UTF16));

    }

    static Stream<Named<String>> textsOverTheLimit() {

        return Stream.of(
                Named.of("한글 1,001자", OVER_LIMIT_TEXT),
                Named.of("a 999개와 😀 하나", OVER_LIMIT_BY_UTF16));

    }

    static Stream<Named<String>> unbindableBodies() {

        return Stream.of(
                Named.of("본문 없음", ""),
                Named.of("닫히지 않은 JSON", "{"),
                Named.of("배열", "[]"),
                Named.of("문자열", "\"답\""),
                Named.of("숫자", "5"),
                Named.of("null", "null"),
                Named.of("text 가 객체", "{\"text\":{},\"source\":\"USER_STATED\"}"),
                Named.of("text 가 배열", "{\"text\":[],\"source\":\"USER_STATED\"}"),
                Named.of("source 가 객체", "{\"text\":\"답\",\"source\":{}}"),
                Named.of("source 가 배열", "{\"text\":\"답\",\"source\":[\"USER_STATED\"]}"));

    }

    static Stream<Arguments> answersDifferentFromTheSavedOne() {

        return Stream.of(
                Arguments.of("다른 답", USER_STATED),
                Arguments.of("저장된 답 ", USER_STATED),
                Arguments.of(" 저장된 답", USER_STATED),
                Arguments.of("저장된 답", "USER_SELECTED"),
                Arguments.of("저장된 답", "COMMIT"),
                Arguments.of("저장된 답", "user_stated"),
                Arguments.of("저장된 답", null),
                Arguments.of(null, USER_STATED),
                Arguments.of(null, null),
                Arguments.of(" ", USER_STATED),
                Arguments.of("저장된 답\u0000", USER_STATED),
                Arguments.of(OVER_LIMIT_TEXT, USER_STATED));

    }

    static Stream<Arguments> blankTexts() {

        return Stream.of(
                Arguments.of(Named.of("빈 글", "")),
                Arguments.of(Named.of("스페이스", "   ")),
                Arguments.of(Named.of("탭·줄바꿈", "\t\n")),
                Arguments.of(Named.of("줄바꿈 없는 공백 U+00A0", "\u00A0")),
                Arguments.of(Named.of("숫자·좁은 줄바꿈 없는 공백 U+2007·U+202F", "\u2007\u202F")),
                Arguments.of(Named.of("전각 공백 U+3000", "\u3000")),
                Arguments.of(Named.of("BOM U+FEFF", "\uFEFF")),
                Arguments.of(Named.of("스페이스와 BOM", "   \uFEFF")),
                Arguments.of(Named.of("스페이스와 자바 공백 U+001C", " \u001C")));

    }

    // T 칸에 답하지 않은 1번 질문이 있는 작성 중인 직접 작성 카드
    @Test
    @DisplayName("넘기기는 200 이고 카드의 현재 버전·문장이 그대로이며 질문 결과만 답 글 없이 SKIPPED 가 된다")
    void skipKeepsCardAndMarksTurnSkipped() throws Exception {

        Long cardId = cardWithOpenTurn();
        fixtures.insertStatement(cardId, 1, "T", "원래 과제", "USER_STATED", null);
        List<Long> before = statementIds(cardId, 1);

        send(myUserId, MY_CARD.toString(), "1", kindBody("SKIP", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.task").value("원래 과제"));

        assertThat(versions(cardId)).extracting(VersionRow::versionNo).containsExactly(1);
        assertThat(statementIds(cardId, 1)).isEqualTo(before);
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText, TurnRow::answerSource)
                .containsExactly(tuple("SKIPPED", null, null));

    }

    @Test
    @DisplayName("넘기기는 text·source 를 보지 않아 모양이 틀린 값이 와도 넘긴다")
    void skipIgnoresTextAndSource() throws Exception {

        Long cardId = cardWithOpenTurn();

        send(myUserId, MY_CARD.toString(), "1", kindBody("SKIP", " ", "COMMIT")).andExpect(status().isOk());

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome).containsExactly("SKIPPED");

    }

    @Test
    @DisplayName("넘긴 질문에 넘기기를 다시 보내면 200 이고 질문·카드가 하나도 바뀌지 않는다")
    void skipResentChangesNothing() throws Exception {

        Long cardId = cardWithOpenTurn();
        send(myUserId, MY_CARD.toString(), "1", kindBody("SKIP", null, null)).andExpect(status().isOk());
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", kindBody("SKIP", null, null)).andExpect(status().isOk());

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("넘긴 질문에 글 답을 보내거나 답한 질문에 넘기기를 보내면 409 INVALID_STATE 이고 아무것도 바뀌지 않는다")
    void otherKindOnClosedTurnIsInvalidState() throws Exception {

        Long skipped = cardWithOpenTurn();
        send(myUserId, MY_CARD.toString(), "1", kindBody("SKIP", null, null)).andExpect(status().isOk());
        List<Map<String, Object>> skippedBefore = stateOf(skipped);
        UUID answeredCard = UUID.randomUUID();
        Long answered = fixtures.insertCard(answeredCard, myUserId);
        interviews.insertAsked(answered, 1, "T", "EVIDENCE_GAP", "USER", "과제는 무엇이었나요?");
        answer(answeredCard, 1, "답", USER_STATED).andExpect(status().isOk());
        List<Map<String, Object>> answeredBefore = stateOf(answered);

        answer(MY_CARD, 1, "답", USER_STATED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        send(myUserId, answeredCard.toString(), "1", kindBody("SKIP", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(stateOf(skipped)).isEqualTo(skippedBefore);
        assertThat(stateOf(answered)).isEqualTo(answeredBefore);

    }

    @Test
    @DisplayName("kind 를 TEXT 로 보내도 kind 없이 보낸 답과 똑같이 저장된다")
    void explicitTextKindIsTheSameAsMissingKind() throws Exception {

        Long cardId = cardWithOpenTurn();

        send(myUserId, MY_CARD.toString(), "1", kindBody("TEXT", "답", USER_STATED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.task").value("답"));

        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerSource)
                .containsExactly(tuple("ANSWERED", "TYPED"));

    }

    @Test
    @DisplayName("보기를 그대로 고른 답(USER_SELECTED)은 카드 문장의 근거 종류가 USER_SELECTED 이고 질문의 출처가 SELECTED 다")
    void selectedAnswerIsStoredAsUserSelected() throws Exception {

        Long cardId = cardWithOpenTurn();

        answer(MY_CARD, 1, "고른 답", "USER_SELECTED").andExpect(status().isOk());

        assertThat(slotsOf(cardId, 2).get("T").evidenceType()).isEqualTo("USER_SELECTED");
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::answerSource).containsExactly("SELECTED");

    }

    @ParameterizedTest
    @ValueSource(strings = {"measured", "skip", "ANSWER", ""})
    @DisplayName("kind 가 TEXT·MEASURED·NOT_MEASURED·SKIP 이 아니면 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void unknownKindIsBadRequest(String kind) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", kindBody(kind, "답", USER_STATED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("넘긴 질문도 질문 상한에 세어, 상한만큼 넘긴 카드는 새 질문을 요청하면 409 INTERVIEW_CAP 이다")
    void skippedTurnsCountTowardTheCap() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "S", "EVIDENCE_GAP", "USER", "상황은요?", "SKIPPED", null, null);
        interviews.insertTurn(cardId, 2, "T", "EVIDENCE_GAP", "USER", "과제는요?", "SKIPPED", null, null);
        interviews.insertAsked(cardId, 3, "A", "EVIDENCE_GAP", "USER", "행동은요?");

        send(myUserId, MY_CARD.toString(), "3", kindBody("SKIP", null, null)).andExpect(status().isOk());

        mvc.perform(post("/api/cards/{id}/interview", MY_CARD).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"R\"}").with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INTERVIEW_CAP"));

    }

    @Test
    @DisplayName("질문 목록의 outcome 은 답한 질문이 ANSWERED, 넘긴 질문이 SKIPPED, 답 전 질문이 null 이다")
    void historyShowsOutcomeOfEachTurn() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "S", "EVIDENCE_GAP", "USER", "상황은요?");
        interviews.insertAsked(cardId, 2, "T", "EVIDENCE_GAP", "USER", "과제는요?");
        interviews.insertAsked(cardId, 3, "A", "EVIDENCE_GAP", "USER", "행동은요?");
        answer(MY_CARD, 1, "답", USER_STATED).andExpect(status().isOk());
        send(myUserId, MY_CARD.toString(), "2", kindBody("SKIP", null, null)).andExpect(status().isOk());

        mvc.perform(get("/api/cards/{id}/interview", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].outcome").value("ANSWERED"))
                .andExpect(jsonPath("$.data[1].outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.data[1].answer").value(nullValue()))
                .andExpect(jsonPath("$.data[2].outcome").value(nullValue()));

    }

    @ParameterizedTest
    @ValueSource(strings = {USER_STATED, "USER_SELECTED"})
    @DisplayName("R 수치 질문에 MEASURED 로 답하면 확정 문장이 R 칸의 새 버전이 되고 질문 결과가 MEASURED 다")
    void measuredReplacesResult(String source) throws Exception {

        Long cardId = cardWithMeasureTurn("커밋으로 쓴 결과", "COMMIT", "MEDIUM");

        send(myUserId, MY_CARD.toString(), "1", kindBody("MEASURED", "응답 시간을 2초에서 0.5초로 줄였다", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.result").value("응답 시간을 2초에서 0.5초로 줄였다"));

        assertThat(versions(cardId)).extracting(VersionRow::versionNo).containsExactly(1, 2);
        assertThat(slotsOf(cardId, 2).get("R").evidenceType()).isEqualTo(source);
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText)
                .containsExactly(tuple("MEASURED", "응답 시간을 2초에서 0.5초로 줄였다"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"MEASURED", "NOT_MEASURED"})
    @DisplayName("R 수치 질문이 아닌 질문에 MEASURED·NOT_MEASURED 로 답하면 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void measureKindsNeedMeasureTurn(String kind) throws Exception {

        Long cardId = cardWithOpenTurn();
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", kindBody(kind, "답", USER_STATED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("지금 화면처럼 R 수치 질문에 kind 없이 글로 답해도 받아서 R 칸을 바꾼다")
    void textAnswerStillWorksOnMeasureTurn() throws Exception {

        Long cardId = cardWithMeasureTurn(null, null, null);

        answer(MY_CARD, 1, "결과", USER_STATED).andExpect(status().isOk());

        assertThat(slotsOf(cardId, 2).get("R").body()).isEqualTo("결과");
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome).containsExactly("ANSWERED");

    }

    @Test
    @DisplayName("MEASURED 는 확정 문장이 필요해 글이 없으면 400 INVALID_REQUEST 이고 질문과 카드는 그대로다")
    void measuredNeedsText() throws Exception {

        Long cardId = cardWithMeasureTurn(null, null, null);
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", kindBody("MEASURED", null, USER_STATED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("NOT_MEASURED 를 글 없이 보내면 카드는 그대로이고 질문 결과만 답 글 없이 NOT_MEASURED 가 된다")
    void notMeasuredWithoutTextOnlyRecords() throws Exception {

        Long cardId = cardWithMeasureTurn(null, null, null);
        List<Long> before = statementIds(cardId, 1);

        send(myUserId, MY_CARD.toString(), "1", kindBody("NOT_MEASURED", null, null)).andExpect(status().isOk());

        assertThat(versions(cardId)).extracting(VersionRow::versionNo).containsExactly(1);
        assertThat(statementIds(cardId, 1)).isEqualTo(before);
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText, TurnRow::answerSource)
                .containsExactly(tuple("NOT_MEASURED", null, null));

    }

    @Test
    @DisplayName("R 이 커밋으로 확인된 문장이면 NOT_MEASURED 에 글이 있어도 R 칸을 지우지 않고 답만 기록한다")
    void notMeasuredKeepsCommitConfirmedResult() throws Exception {

        Long cardId = cardWithMeasureTurn("커밋으로 쓴 결과", "COMMIT", "MEDIUM");
        List<Map<String, Object>> statementsBefore = jdbc.queryForList(
                "SELECT id, version_no, body FROM card_statement WHERE card_id = ? ORDER BY id", cardId);

        send(myUserId, MY_CARD.toString(), "1", kindBody("NOT_MEASURED", "응답이 빨라졌다", USER_STATED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.result").value("커밋으로 쓴 결과"));

        assertThat(versions(cardId)).extracting(VersionRow::versionNo).containsExactly(1);
        assertThat(jdbc.queryForList("SELECT id, version_no, body FROM card_statement WHERE card_id = ? ORDER BY id",
                cardId)).isEqualTo(statementsBefore);
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome, TurnRow::answerText, TurnRow::answerSource)
                .containsExactly(tuple("NOT_MEASURED", "응답이 빨라졌다", "TYPED"));

    }

    @ParameterizedTest
    @MethodSource("resultsTheUserMayReplace")
    @DisplayName("R 이 비었거나 확인 필요이거나 사용자가 쓴 글이면 NOT_MEASURED 의 글이 R 칸의 새 버전이 된다")
    void notMeasuredReplacesUnconfirmedResult(String body, String evidenceType, String confidence) throws Exception {

        Long cardId = cardWithMeasureTurn(body, evidenceType, confidence);

        send(myUserId, MY_CARD.toString(), "1", kindBody("NOT_MEASURED", "응답이 빨라졌다", USER_STATED))
                .andExpect(status().isOk());

        assertThat(versions(cardId)).extracting(VersionRow::versionNo).containsExactly(1, 2);
        assertThat(slotsOf(cardId, 2).get("R").body()).isEqualTo("응답이 빨라졌다");
        assertThat(interviews.turnsOf(cardId)).extracting(TurnRow::outcome).containsExactly("NOT_MEASURED");

    }

    @ParameterizedTest
    @MethodSource("measureAnswers")
    @DisplayName("측정 답으로 끝난 질문에 같은 요청을 다시 보내면 200 이고 아무것도 바뀌지 않으며, 다른 종류의 답은 409 INVALID_STATE 다")
    void measureAnswerResentIsIdempotent(String kind, String text, String source) throws Exception {

        Long cardId = cardWithMeasureTurn("커밋으로 쓴 결과", "COMMIT", "MEDIUM");
        send(myUserId, MY_CARD.toString(), "1", kindBody(kind, text, source)).andExpect(status().isOk());
        List<Map<String, Object>> before = stateOf(cardId);

        send(myUserId, MY_CARD.toString(), "1", kindBody(kind, text, source)).andExpect(status().isOk());
        answer(MY_CARD, 1, text == null ? "글 답" : text, USER_STATED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(stateOf(cardId)).isEqualTo(before);

    }

    @Test
    @DisplayName("답을 저장해도 새 질문은 생기지 않고, 다음 질문은 질문 요청에서만 만든다")
    void answerDoesNotCreateFollowUp() throws Exception {

        Long cardId = cardWithOpenTurn();

        answer(MY_CARD, 1, "짧음", USER_STATED).andExpect(status().isOk());

        assertThat(interviews.turnsOf(cardId)).hasSize(1);

    }

    static Stream<Arguments> resultsTheUserMayReplace() {

        return Stream.of(
                Arguments.of(Named.of("빈 R", null), null, null),
                Arguments.of(Named.of("확인 필요 R", "흐린 결과"), "COMMIT", "LOW"),
                Arguments.of(Named.of("사용자가 쓴 R", "내가 쓴 결과"), "USER_STATED", null));

    }

    static Stream<Arguments> measureAnswers() {

        return Stream.of(
                Arguments.of(Named.of("MEASURED", "MEASURED"), "응답 시간을 0.5초로 줄였다", USER_STATED),
                Arguments.of(Named.of("글 없는 NOT_MEASURED", "NOT_MEASURED"), null, null),
                Arguments.of(Named.of("글 있는 NOT_MEASURED", "NOT_MEASURED"), "응답이 빨라졌다", "USER_SELECTED"));

    }

    // R 칸의 1번 질문이 수치 질문(MEASURE)으로 열려 있고, body 가 있으면 1번 버전 R 칸에 그 문장이 있는 카드
    private Long cardWithMeasureTurn(String body, String evidenceType, String confidence) {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        if (body != null) {
            Long statement = fixtures.insertStatement(cardId, 1, "R", body, evidenceType, confidence);
            if ("COMMIT".equals(evidenceType)) {
                fixtures.insertStatementEvidence(statement, firstCommit);
            }
        }
        interviews.insertAsked(cardId, 1, "R", "EVIDENCE_GAP", "USER", "얼마나 빨라졌나요?");
        jdbc.update("UPDATE interview_turn SET answer_mode = 'MEASURE' WHERE card_id = ?", cardId);

        return cardId;

    }

    @Test
    @DisplayName("다듬은 문장을 TEXT 답으로 저장하며 다듬기 전 답(original)을 함께 남기고, 지난 질문 목록의 answer.original 로 돌려준다")
    void savesTheOriginalOfAPolishedAnswer() throws Exception {

        Long cardId = cardWithOpenTurn();

        send(myUserId, MY_CARD.toString(), "1", originalBody("TEXT", "과제를 나눠 맡았다.", "과제 나눠서 했어요"))
                .andExpect(status().isOk());

        assertThat(originalOf(cardId)).isEqualTo("과제 나눠서 했어요");
        mvc.perform(get("/api/cards/{id}/interview", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].answer.text").value("과제를 나눠 맡았다."))
                .andExpect(jsonPath("$.data[0].answer.original").value("과제 나눠서 했어요"));

    }

    @ParameterizedTest
    @MethodSource("answersKeepingNoOriginal")
    @DisplayName("다듬기 전 답은 TEXT 답에만 남기고, 공백뿐이면 남기지 않는다")
    void originalIsKeptOnlyForTextAnswers(String body) throws Exception {

        Long cardId = cardWithOpenTurn();

        send(myUserId, MY_CARD.toString(), "1", body).andExpect(status().isOk());

        assertThat(originalOf(cardId)).isNull();

    }

    @ParameterizedTest
    @MethodSource("invalidOriginals")
    @DisplayName("다듬기 전 답이 1,000자를 넘거나 U+0000 이 있으면 400 INVALID_REQUEST 이고 답을 저장하지 않는다")
    void invalidOriginalIsRejected(String original) throws Exception {

        Long cardId = cardWithOpenTurn();

        send(myUserId, MY_CARD.toString(), "1", originalBody("TEXT", "과제를 나눠 맡았다.", original))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(jdbc.queryForObject("SELECT outcome FROM interview_turn WHERE card_id = ?", String.class, cardId))
                .isNull();

    }

    static Stream<Arguments> answersKeepingNoOriginal() {

        return Stream.of(
                Arguments.of(Named.of("넘기기", "{\"kind\":\"SKIP\",\"original\":\"과제 나눠서 했어요\"}")),
                Arguments.of(Named.of("공백뿐인 원래 답",
                        "{\"kind\":\"TEXT\",\"text\":\"과제를 나눠 맡았다.\",\"source\":\"USER_STATED\",\"original\":\"   \"}")));

    }

    static Stream<Arguments> invalidOriginals() {

        return Stream.of(
                Arguments.of(Named.of("1,001자", OVER_LIMIT_TEXT)),
                Arguments.of(Named.of("U+0000", "과제\u0000나눠서 했어요")));

    }

    private String originalOf(Long cardId) {

        return jdbc.queryForObject("SELECT answer_original FROM interview_turn WHERE card_id = ?", String.class, cardId);

    }

    private String originalBody(String kind, String text, String original) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", kind);
        fields.put("text", text);
        fields.put("source", USER_STATED);
        fields.put("original", original);

        return mapper.writeValueAsString(fields);

    }

    private Long cardWithOpenTurn() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertAsked(cardId, 1, "T", "EVIDENCE_GAP", "USER", "과제는 무엇이었나요?");

        return cardId;

    }

    private Long confirmedCardWithOpenTurn() {

        Long cardId = cardWithOpenTurn();
        fixtures.insertStatement(cardId, 1, "S", "상황", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.insertStatement(cardId, 2, "S", "상황", "USER_STATED", null);
        fixtures.confirmCard(cardId);

        return cardId;

    }

    private Long othersCardWithOpenTurn() {

        // 남의 카드 id 가 내 사용자 id 와 같아지면 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 먼저 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long cardId = fixtures.insertCard(OTHERS_CARD, othersUserId);
        interviews.insertAsked(cardId, 1, "T", "EVIDENCE_GAP", "USER", "남의 질문");

        return cardId;

    }

    // S 는 커밋 근거 둘이 붙은 글, T 는 앞서 답한 1번 질문의 글, A·R 은 확인이 필요한 커밋 글이며, A 칸의 2번 질문이 열려 있다
    private Rich richCard() {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long answeredTurn = interviews.insertAnswered(cardId, 1, "T", "옛 답", "TYPED");
        Long openTurn = interviews.insertAsked(cardId, 2, "A", "EVIDENCE_GAP", "USER", "행동을 더 알려 주세요");
        Long situation = fixtures.insertStatement(cardId, 1, "S", "커밋으로 쓴 상황", "COMMIT", "MEDIUM");
        fixtures.insertStatementEvidence(situation, firstCommit);
        fixtures.insertStatementEvidence(situation, secondCommit);
        fixtures.insertStatement(cardId, 1, "T", "옛 답", "USER_STATED", null, answeredTurn);
        Long action = fixtures.insertStatement(cardId, 1, "A", "흐린 행동", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(action, firstCommit);
        Long result = fixtures.insertStatement(cardId, 1, "R", "흐린 결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, secondCommit);

        return new Rich(cardId, answeredTurn, openTurn);

    }

    private List<MvcResult> answerTwiceAtOnce(Long cardId, String firstText, String secondText) throws Exception {

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        try {
            // 두 요청이 모두 같은 카드 행에서 기다리게 만든 뒤 풀어, 앞 요청이 저장한 답을 뒤 요청이 보는지 확인한다
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM card WHERE id = ? FOR UPDATE", Long.class, cardId);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> first = pool.submit(() -> answer(MY_CARD, 1, firstText, USER_STATED).andReturn());
            Future<MvcResult> second = pool.submit(() -> answer(MY_CARD, 1, secondText, USER_STATED).andReturn());
            awaitLockWaiters(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

    }

    private void awaitLockWaiters(int count) throws InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }

        throw new AssertionError("잠금을 기다리는 요청이 " + count + "개가 되지 않았다");

    }

    private static void awaitQuietly(CountDownLatch latch) {

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private ResultActions answer(UUID cardId, int turnNo, String text, String source) throws Exception {

        return send(myUserId, cardId.toString(), String.valueOf(turnNo), body(text, source));

    }

    private ResultActions send(Long userId, String id, String turnNo, String content) throws Exception {

        return mvc.perform(answerRequest(id, turnNo, content).with(loggedInAs(userId)).with(csrf()));

    }

    private static MockHttpServletRequestBuilder answerRequest(String id, String turnNo, String content) {

        return post(ANSWER_PATH, id, turnNo).contentType(MediaType.APPLICATION_JSON).content(content);

    }

    private String body(String text, String source) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", text);
        fields.put("source", source);

        return mapper.writeValueAsString(fields);

    }

    private String kindBody(String kind, String text, String source) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", kind);
        fields.put("text", text);
        fields.put("source", source);

        return mapper.writeValueAsString(fields);

    }

    private ResultActions saveDraft(String situation, String task, String action, String result) throws Exception {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("situation", situation);
        fields.put("task", task);
        fields.put("action", action);
        fields.put("result", result);

        return mvc.perform(patch("/api/cards/{id}/draft", MY_CARD)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields))
                .with(loggedInAs(myUserId)).with(csrf()));

    }

    private static String bodyOf(ResultActions actions) throws Exception {

        return contentOf(actions.andReturn());

    }

    private static String contentOf(MvcResult result) throws Exception {

        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);

    }

    private static Map<String, Object> dataOf(String body) {

        return JsonPath.read(body, "$.data");

    }

    private void setUpdatedAt(Long cardId, String instant) {

        jdbc.update("UPDATE card SET updated_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC), cardId);

    }

    private List<Map<String, Object>> stateOf(Long cardId) {

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT current_version, status, updated_at, confirmed_at FROM card WHERE id = ?",
                cardId));
        rows.addAll(jdbc.queryForList(
                "SELECT version_no, source, is_confirmed, updated_at FROM card_version WHERE card_id = ? ORDER BY version_no",
                cardId));
        rows.addAll(jdbc.queryForList(
                "SELECT id, version_no, star_slot, seq, body, evidence_type, confidence, source_turn_id FROM card_statement WHERE card_id = ? ORDER BY id",
                cardId));
        rows.addAll(jdbc.queryForList(
                "SELECT e.id, e.statement_id, e.commit_id FROM statement_evidence e JOIN card_statement s ON s.id = e.statement_id WHERE s.card_id = ? ORDER BY e.id",
                cardId));
        // 배열 칸(suggested_answers)은 JDBC 객체끼리 같은지 비교되지 않아 행 전체를 JSON 글로 바꿔 비교한다
        rows.addAll(jdbc.queryForList("SELECT to_jsonb(t)::text AS turn FROM interview_turn t WHERE card_id = ? ORDER BY seq",
                cardId));

        return rows;

    }

    private List<VersionRow> versions(Long cardId) {

        return jdbc.query("SELECT version_no, source, is_confirmed FROM card_version WHERE card_id = ? ORDER BY version_no",
                (row, rowNum) -> new VersionRow(row.getInt("version_no"), row.getString("source"),
                        row.getBoolean("is_confirmed")),
                cardId);

    }

    private Map<String, StatementRow> slotsOf(Long cardId, int versionNo) {

        Map<String, StatementRow> slots = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, star_slot, body, evidence_type, confidence, source_turn_id FROM card_statement WHERE card_id = ? AND version_no = ? ORDER BY id",
                cardId, versionNo)) {
            List<Long> commitIds = jdbc.queryForList(
                    "SELECT commit_id FROM statement_evidence WHERE statement_id = ? ORDER BY id", Long.class, row.get("id"));
            slots.put(((String) row.get("star_slot")).trim(), new StatementRow((String) row.get("body"),
                    (String) row.get("evidence_type"), (String) row.get("confidence"), (Long) row.get("source_turn_id"),
                    commitIds));
        }

        return slots;

    }

    private List<Long> statementIds(Long cardId, int versionNo) {

        return jdbc.queryForList("SELECT id FROM card_statement WHERE card_id = ? AND version_no = ? ORDER BY id",
                Long.class, cardId, versionNo);

    }

    private int currentVersion(Long cardId) {

        return jdbc.queryForObject("SELECT current_version FROM card WHERE id = ?", Integer.class, cardId);

    }

    private Instant cardUpdatedAt(Long cardId) {

        return jdbc.queryForObject("SELECT updated_at FROM card WHERE id = ?", OffsetDateTime.class, cardId).toInstant();

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }

    private record Rich(Long cardId, Long answeredTurnId, Long openTurnId) {
    }

    private record VersionRow(int versionNo, String source, boolean confirmed) {
    }

    private record StatementRow(String body, String evidenceType, String confidence, Long sourceTurnId,
                                List<Long> commitIds) {
    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
