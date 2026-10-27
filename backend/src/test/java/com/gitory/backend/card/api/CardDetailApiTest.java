package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.InterviewFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.assertj.core.groups.Tuple.tuple;
import static org.hamcrest.Matchers.contains;
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
class CardDetailApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    // FE schemas.ts 의 Card·CardVersion·Evidence 가 요구하는 키로, 값이 null 이어도 키는 빠지면 안 된다
    private static final String[] CARD_KEYS = {"id", "kind", "status", "title", "areas", "techs", "period", "repo",
            "candidate", "version", "evidence", "lowConfidenceFields", "droppedFields", "maskRules", "generation",
            "draftFailure", "interviewTurns", "confirmedAt", "createdAt"};
    private static final String[] VERSION_KEYS = {"versionNo", "source", "createdAt", "situation", "task", "action",
            "result"};
    private static final String[] EVIDENCE_KEYS = {"field", "type", "authoredBy", "sha", "url", "snippet", "turnNo"};

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
    private Long myRepoId;
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
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        firstCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        secondCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @Test
    @DisplayName("내 카드를 조회하면 200 으로 현재 버전 번호·출처·마지막 저장 시각과 네 칸 글, 기간·저장소·저장 순서 그대로인 가리기 규칙·상태·종류·확정 시각·만든 시각이 온다")
    void returnsCardOfCurrentVersion() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        jdbc.update("""
                UPDATE card SET title = ?, period = ?, user_repository_id = ?, mask_rules = ?::jsonb,
                                created_at = '2026-01-01T00:00:00Z'
                WHERE id = ?
                """, "로그인 세션 개선", "2024년 상반기", myRepoId,
                "[{\"from\": \"우리 팀\", \"to\": \"팀\"}, {\"from\": \"A사\", \"to\": \"\"}]", cardId);
        fixtures.insertCardVersion(cardId, 2, "RESTORE", false);
        jdbc.update("UPDATE card_version SET updated_at = '2026-02-02T02:02:02Z' WHERE card_id = ? AND version_no = 2",
                cardId);
        fixtures.insertStatement(cardId, 2, "S", "  앞뒤 공백까지 쓴 상황  ", "USER_STATED", null);
        fixtures.insertStatement(cardId, 2, "A", "직접 한 행동", "USER_STATED", null);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.id").value(MY_CARD.toString()))
                .andExpect(jsonPath("$.data.kind").value("QUALITATIVE"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.title").value("로그인 세션 개선"))
                .andExpect(jsonPath("$.data.period").value("2024년 상반기"))
                .andExpect(jsonPath("$.data.repo.id").value(MY_REPO.toString()))
                .andExpect(jsonPath("$.data.repo.owner").value("grow22"))
                .andExpect(jsonPath("$.data.repo.name").value("gitory"))
                .andExpect(jsonPath("$.data.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.version.source").value("RESTORE"))
                .andExpect(jsonPath("$.data.version.createdAt").value("2026-02-02T02:02:02Z"))
                .andExpect(jsonPath("$.data.version.situation").value("  앞뒤 공백까지 쓴 상황  "))
                .andExpect(jsonPath("$.data.version.task").value(nullValue()))
                .andExpect(jsonPath("$.data.version.action").value("직접 한 행동"))
                .andExpect(jsonPath("$.data.version.result").value(nullValue()))
                .andExpect(jsonPath("$.data.maskRules", hasSize(2)))
                .andExpect(jsonPath("$.data.maskRules[0].from").value("우리 팀"))
                .andExpect(jsonPath("$.data.maskRules[0].to").value("팀"))
                .andExpect(jsonPath("$.data.maskRules[1].from").value("A사"))
                .andExpect(jsonPath("$.data.maskRules[1].to").value(""))
                .andExpect(jsonPath("$.data.confirmedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").value("2026-01-01T00:00:00Z"));

    }

    @Test
    @DisplayName("카드에 저장된 분야·기술 태그를 순서대로 싣는다")
    void sendsTags() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        jdbc.update("UPDATE card SET areas = '{DB,BACKEND}', techs = '{SQL,JAVA}' WHERE id = ?", cardId);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areas").value(contains("DB", "BACKEND")))
                .andExpect(jsonPath("$.data.techs").value(contains("SQL", "JAVA")));

    }

    @Test
    @DisplayName("태그가 없는 카드는 분야·기술을 null 이 아닌 빈 배열로 싣는다")
    void sendsEmptyTags() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areas").isArray())
                .andExpect(jsonPath("$.data.areas").isEmpty())
                .andExpect(jsonPath("$.data.techs").isArray())
                .andExpect(jsonPath("$.data.techs").isEmpty());

    }

    @Test
    @DisplayName("이전 버전 문장은 섞이지 않고 현재 버전의 문장만 보인다")
    void showsOnlyCurrentVersionStatements() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertStatement(cardId, 1, "S", "옛 상황", "USER_STATED", null);
        fixtures.insertStatement(cardId, 1, "T", "옛 과제", "USER_STATED", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        fixtures.insertStatement(cardId, 2, "S", "새 상황", "USER_STATED", null);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.version.situation").value("새 상황"))
                .andExpect(jsonPath("$.data.version.task").value(nullValue()))
                .andExpect(jsonPath("$.data.evidence", hasSize(1)))
                .andExpect(jsonPath("$.data.evidence[0].field").value("S"));

    }

    @Test
    @DisplayName("근거는 S·T·A·R 순서로, 사용자가 쓰거나 고른 문장은 칸마다 한 줄(USER, sha·url·snippet 은 null, turnNo 는 연결된 질문 번호)이고 커밋 문장은 연결된 커밋마다 한 줄(AI)이다")
    void listsEvidenceInFieldOrder() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long result = fixtures.insertStatement(cardId, 1, "R", "커밋으로 쓴 결과", "COMMIT", "MEDIUM");
        fixtures.insertStatementEvidence(result, firstCommit);
        Long action = fixtures.insertStatement(cardId, 1, "A", "커밋으로 쓴 행동", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(action, secondCommit);
        fixtures.insertStatementEvidence(action, firstCommit);
        Long turn = fixtures.insertInterviewTurn(cardId, 1, "T");
        fixtures.insertStatement(cardId, 1, "T", "고른 답", "USER_SELECTED", null, turn);
        fixtures.insertStatement(cardId, 1, "S", "직접 쓴 상황", "USER_STATED", null);

        String body = card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> evidence = JsonPath.read(body, "$.data.evidence");
        assertThat(evidence).extracting(row -> row.get("field"), row -> row.get("type"), row -> row.get("authoredBy"))
                .containsExactly(
                        tuple("S", "USER_STATED", "USER"),
                        tuple("T", "USER_SELECTED", "USER"),
                        tuple("A", "COMMIT", "AI"),
                        tuple("A", "COMMIT", "AI"),
                        tuple("R", "COMMIT", "AI"));
        assertThat(evidence).allSatisfy(row -> assertThat(row).containsOnlyKeys(EVIDENCE_KEYS));
        assertThat(evidence.subList(0, 2)).allSatisfy(row -> assertThat(row)
                .containsEntry("sha", null).containsEntry("url", null).containsEntry("snippet", null));
        assertThat(evidence).extracting(row -> row.get("turnNo")).containsExactly(null, 1, null, null, null);

    }

    @Test
    @DisplayName("커밋 근거는 그 커밋의 앞 7자리 sha 와 카드 저장소의 GitHub 커밋 주소를 싣고, 저장소가 없는 카드는 sha 만 싣고 주소를 지어내지 않는다")
    void commitEvidenceCarriesShortShaAndGithubUrl() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", myRepoId, cardId);
        Long action = fixtures.insertStatement(cardId, 1, "A", "커밋으로 쓴 행동", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(action, firstCommit);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence", hasSize(1)))
                .andExpect(jsonPath("$.data.evidence[0].sha").value("1111111"))
                .andExpect(jsonPath("$.data.evidence[0].url")
                        .value("https://github.com/grow22/gitory/commit/1111111111111111111111111111111111111111"));

        jdbc.update("UPDATE card SET user_repository_id = NULL WHERE id = ?", cardId);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].sha").value("1111111"))
                .andExpect(jsonPath("$.data.evidence[0].url").value(nullValue()));

    }

    @Test
    @DisplayName("사용자 근거의 turnNo 는 그 칸 문장이 가리키는 질문의 번호이고, 질문과 이어지지 않은 글과 커밋 근거는 질문에 이어져 있어도 null 이다")
    void evidenceCarriesTheNumberOfTheLinkedTurn() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long firstTurn = fixtures.insertInterviewTurn(cardId, 1, "S");
        Long secondTurn = fixtures.insertInterviewTurn(cardId, 2, "T");
        fixtures.insertInterviewTurn(cardId, 3, "R");
        fixtures.insertStatement(cardId, 1, "S", "첫 질문에 답한 상황", "USER_STATED", null, firstTurn);
        fixtures.insertStatement(cardId, 1, "T", "둘째 질문에서 고른 과제", "USER_SELECTED", null, secondTurn);
        fixtures.insertStatement(cardId, 1, "A", "질문 없이 쓴 행동", "USER_STATED", null);
        Long result = fixtures.insertStatement(cardId, 1, "R", "질문에 이어진 커밋 글", "COMMIT", "HIGH", firstTurn);
        fixtures.insertStatementEvidence(result, firstCommit);

        String body = card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> evidence = JsonPath.read(body, "$.data.evidence");
        assertThat(evidence).extracting(row -> row.get("field"), row -> row.get("type"), row -> row.get("turnNo"))
                .containsExactly(
                        tuple("S", "USER_STATED", 1),
                        tuple("T", "USER_SELECTED", 2),
                        tuple("A", "USER_STATED", null),
                        tuple("R", "COMMIT", null));

    }

    @Test
    @DisplayName("interviewTurns 는 결과가 ANSWERED 인 질문 수이고, 답하지 않았거나 LATER·SKIPPED·INSUFFICIENT 로 끝난 질문은 답 글이 남아 있어도 세지 않으며 다른 카드의 질문도 세지 않는다")
    void countsOnlyAnsweredTurns() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        interviews.insertTurn(cardId, 1, "S", "EVIDENCE_GAP", "USER", "질문", "ANSWERED", "직접 쓴 답", "TYPED");
        interviews.insertAsked(cardId, 2, "T", "EVIDENCE_GAP", "USER", "아직 답하지 않은 질문");
        interviews.insertTurn(cardId, 3, "A", "EVIDENCE_GAP", "USER", "질문", "LATER", null, null);
        interviews.insertTurn(cardId, 4, "R", "EVIDENCE_GAP", "USER", "질문", "SKIPPED", null, null);
        interviews.insertTurn(cardId, 5, "S", "FOLLOWUP", "AUTO", "질문", "INSUFFICIENT", "남은 답", "TYPED");
        interviews.insertTurn(cardId, 6, "T", "FOLLOWUP", "USER", "질문", "ANSWERED", "고른 답", "SELECTED");
        Long otherCardId = fixtures.insertCard(UUID.randomUUID(), myUserId);
        interviews.insertAnswered(otherCardId, 1, "S", "다른 카드의 답", "TYPED");

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewTurns").value(2));

    }

    @Test
    @DisplayName("현재 문장의 확신도가 LOW 인 칸만 S·T·A·R 순서로 lowConfidenceFields 에 field 와 빈 why 로 실린다")
    void listsLowConfidenceFields() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "흐린 상황", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(situation, firstCommit);
        Long task = fixtures.insertStatement(cardId, 1, "T", "보통 과제", "COMMIT", "MEDIUM");
        fixtures.insertStatementEvidence(task, firstCommit);
        fixtures.insertStatement(cardId, 1, "A", "직접 쓴 행동", "USER_STATED", null);
        Long result = fixtures.insertStatement(cardId, 1, "R", "흐린 결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, secondCommit);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lowConfidenceFields", hasSize(2)))
                .andExpect(jsonPath("$.data.lowConfidenceFields[0].field").value("S"))
                .andExpect(jsonPath("$.data.lowConfidenceFields[0].why").value(""))
                .andExpect(jsonPath("$.data.lowConfidenceFields[1].field").value("R"))
                .andExpect(jsonPath("$.data.lowConfidenceFields[1].why").value(""));

    }

    @Test
    @DisplayName("확인 필요 칸의 why 는 그 칸 메모의 이유 문장이고, 메모가 없는 확인 필요 칸은 빈 글이다")
    void fillsWhyFromReviewNote() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        Long situation = fixtures.insertStatement(cardId, 1, "S", "흐린 상황", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(situation, firstCommit);
        fixtures.insertSlotNote(cardId, 1, "S", null, "근거에 없는 수치: 30%");
        Long result = fixtures.insertStatement(cardId, 1, "R", "흐린 결과", "COMMIT", "LOW");
        fixtures.insertStatementEvidence(result, secondCommit);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lowConfidenceFields", hasSize(2)))
                .andExpect(jsonPath("$.data.lowConfidenceFields[0].field").value("S"))
                .andExpect(jsonPath("$.data.lowConfidenceFields[0].why").value("근거에 없는 수치: 30%"))
                .andExpect(jsonPath("$.data.lowConfidenceFields[1].field").value("R"))
                .andExpect(jsonPath("$.data.lowConfidenceFields[1].why").value(""));

    }

    @Test
    @DisplayName("비운 이유 메모가 있는 빈 칸만 S·T·A·R 순서로 droppedFields 에 이유와 함께 실리고, 글이 있는 칸과 메모 없는 빈 칸은 싣지 않는다")
    void listsDroppedFieldsFromNotes() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertSlotNote(cardId, 1, "R", "TIMEOUT", null);
        fixtures.insertSlotNote(cardId, 1, "S", "NO_EVIDENCE", null);
        fixtures.insertStatement(cardId, 1, "T", "직접 쓴 과제", "USER_STATED", null);
        fixtures.insertSlotNote(cardId, 1, "T", "NO_EVIDENCE", null);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.droppedFields", hasSize(2)))
                .andExpect(jsonPath("$.data.droppedFields[0].field").value("S"))
                .andExpect(jsonPath("$.data.droppedFields[0].reason").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.data.droppedFields[1].field").value("R"))
                .andExpect(jsonPath("$.data.droppedFields[1].reason").value("TIMEOUT"));

    }

    @Test
    @DisplayName("칸 메모는 현재 버전의 것만 읽어, 앞 버전에서 비웠다 채운 칸의 이유는 싣지 않는다")
    void readsNotesOfCurrentVersionOnly() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertSlotNote(cardId, 1, "R", "NO_EVIDENCE", null);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.versionNo").value(2))
                .andExpect(jsonPath("$.data.droppedFields", hasSize(0)));

    }

    @Test
    @DisplayName("남의 카드를 조회하면 없는 카드와 똑같이 404 NOT_FOUND 이고 그 카드로 VIEW_CARD 거절 기록이 하나 남는다")
    void deniesOthersCard() throws Exception {

        // 남의 카드 id 가 사용자 id 와 겹치면 거절 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);

        card(myUserId, OTHERS_CARD.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_CARD", othersCardId, "DENIED"));

    }

    @ParameterizedTest
    @ValueSource(strings = {"44444444-4444-4444-8444-444444444444", "not-a-uuid"})
    @DisplayName("없는 카드나 UUID 가 아닌 id 면 404 NOT_FOUND 이고 거절 기록은 남지 않는다")
    void unknownCardIsNotFound(String id) throws Exception {

        card(myUserId, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("확정된 카드도 그대로 조회되고 상태는 CONFIRMED, 확정 시각이 실린다")
    void readsConfirmedCard() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(cardId);
        jdbc.update("UPDATE card SET confirmed_at = '2026-03-03T03:03:03Z' WHERE id = ?", cardId);

        card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedAt").value("2026-03-03T03:03:03Z"))
                .andExpect(jsonPath("$.data.version.versionNo").value(2));

    }

    @Test
    @DisplayName("로그인하지 않고 조회하면 401 UNAUTHENTICATED 이다")
    void requiresLogin() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        mvc.perform(get("/api/cards/{id}", MY_CARD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    }

    @Test
    @DisplayName("FE 가 필수로 읽는 키는 값이 null 이어도 빠지지 않는다(candidate·generation·confirmedAt·period·repo 와 빈 칸 글)")
    void keepsRequiredKeysEvenWhenNull() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        String body = card(myUserId, MY_CARD.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> card = JsonPath.read(body, "$.data");
        assertThat(card).containsOnlyKeys(CARD_KEYS)
                .containsEntry("candidate", null).containsEntry("generation", null)
                .containsEntry("confirmedAt", null).containsEntry("period", null).containsEntry("repo", null);
        Map<String, Object> version = JsonPath.read(body, "$.data.version");
        assertThat(version).containsOnlyKeys(VERSION_KEYS)
                .containsEntry("situation", null).containsEntry("task", null)
                .containsEntry("action", null).containsEntry("result", null);

    }

    private ResultActions card(Long userId, String id) throws Exception {

        return mvc.perform(get("/api/cards/{id}", id).with(loggedInAs(userId)));

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
