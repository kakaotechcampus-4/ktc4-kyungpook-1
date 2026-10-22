package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.TestFixtures;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class CardDeleteApiTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID KEPT_CARD = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private CandidateCardFixtures candidates;

    private Long myUserId;
    private Long othersUserId;
    private Long myRepoId;
    private Long commitId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        candidates = new CandidateCardFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        commitId = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");

    }

    @Test
    @DisplayName("내 작성 중 카드를 지우면 200 과 ok true 를 주고, 그 뒤 상세 조회는 404 다")
    void deletesDraftCard() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        deleteCard(MY_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()));

        mvc.perform(get("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(cardCount(MY_CARD)).isZero();

    }

    @Test
    @DisplayName("확정 카드도 지울 수 있다")
    void deletesConfirmedCard() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(cardId);

        deleteCard(MY_CARD).andExpect(status().isOk());

        assertThat(cardCount(MY_CARD)).isZero();

    }

    @Test
    @DisplayName("카드를 지우면 버전·문장·근거·칸 메모·되묻기 질문과 보기가 같이 지워지고, 다른 카드의 행은 그대로다")
    void deletesEveryRowOfTheCardOnly() throws Exception {

        Long cardId = cardWithEveryChild(MY_CARD);
        Long keptId = cardWithEveryChild(KEPT_CARD);
        Map<String, Integer> kept = rowsOf(keptId);

        deleteCard(MY_CARD).andExpect(status().isOk());

        assertThat(rowsOf(cardId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "card_version", 0, "card_statement", 0, "statement_evidence", 0, "card_slot_note", 0,
                "interview_turn", 0, "interview_option", 0));
        assertThat(rowsOf(keptId)).isEqualTo(kept);
        assertThat(kept).allSatisfy((table, count) -> assertThat(count).isPositive());

    }

    @Test
    @DisplayName("후보로 만든 카드를 지우면 그 후보가 NEW 로 돌아가고, 같은 후보로 카드를 다시 만들 수 있다")
    void releasesCandidateSoItCanBeUsedAgain() throws Exception {

        Long candidateId = candidates.insertCandidate(myRepoId, "PR", 42, "#42", "이메일 발송", "USED");
        UUID cardId = candidateCard(candidateId, "SUCCEEDED");

        deleteCard(cardId).andExpect(status().isOk());

        assertThat(candidates.statusOf(candidateId)).isEqualTo("NEW");
        mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateIds\":[\"" + candidates.publicIdOf(candidateId) + "\"]}")
                        .with(loggedInAs(myUserId))
                        .with(csrf()))
                .andExpect(status().isOk());
        assertThat(candidates.statusOf(candidateId)).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card WHERE candidate_id = ?", Integer.class,
                candidateId)).isEqualTo(1);

    }

    @Test
    @DisplayName("직접 작성 카드를 지워도 후보 표는 바뀌지 않는다")
    void manualCardLeavesCandidatesAsIs() throws Exception {

        Long used = candidates.insertCandidate(myRepoId, "PR", 42, "#42", "이메일 발송", "USED");
        Long excluded = candidates.insertCandidate(myRepoId, "PR", 7, "#7", "제외한 후보", "EXCLUDED");
        fixtures.insertCard(MY_CARD, myUserId);

        deleteCard(MY_CARD).andExpect(status().isOk());

        assertThat(candidates.statusOf(used)).isEqualTo("USED");
        assertThat(candidates.statusOf(excluded)).isEqualTo("EXCLUDED");

    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING"})
    @DisplayName("초안 Job 이 대기 중이거나 실행 중인 카드는 409 INVALID_STATE 이고 카드도 후보도 그대로다")
    void refusesWhileDraftJobIsActive(String state) throws Exception {

        Long candidateId = candidates.insertCandidate(myRepoId, "PR", 42, "#42", "이메일 발송", "USED");
        UUID cardId = candidateCard(candidateId, state);

        deleteCard(cardId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(cardCount(cardId)).isEqualTo(1);
        assertThat(candidates.statusOf(candidateId)).isEqualTo("USED");
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("없는 카드·UUID 가 아닌 id·이미 지운 카드는 404 이고 거절 기록을 남기지 않는다")
    void unknownMalformedOrDeletedCardIsNotFound() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);
        deleteCard(MY_CARD).andExpect(status().isOk());

        deleteCard(MY_CARD)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        deleteCard(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(delete("/api/cards/{id}", "not-a-uuid").with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "DELETE_CARD", cardId, "OK"));

    }

    @Test
    @DisplayName("남의 카드는 없는 카드와 같은 404 이고, DELETE_CARD 거절 기록을 남기며 카드는 그대로다")
    void othersCardIsNotFoundAndDenied() throws Exception {

        // 남의 카드 id 가 내 사용자 id 와 같아지면 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 먼저 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);

        deleteCard(OTHERS_CARD)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events()).containsExactly(new AuditRow(myUserId, "DELETE_CARD", othersCardId, "DENIED"));
        assertThat(cardCount(OTHERS_CARD)).isEqualTo(1);

    }

    @Test
    @DisplayName("지우는 데 성공하면 지운 카드 id 로 DELETE_CARD OK 기록을 남긴다")
    void recordsDeletion() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, myUserId);

        deleteCard(MY_CARD).andExpect(status().isOk());

        assertThat(events()).containsExactly(new AuditRow(myUserId, "DELETE_CARD", cardId, "OK"));

    }

    @Test
    @DisplayName("로그인하지 않으면 401, CSRF 토큰이 없으면 403 이고 카드는 그대로다")
    void deleteNeedsSessionAndCsrf() throws Exception {

        fixtures.insertCard(MY_CARD, myUserId);

        mvc.perform(delete("/api/cards/{id}", MY_CARD).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(delete("/api/cards/{id}", MY_CARD).with(loggedInAs(myUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(cardCount(MY_CARD)).isEqualTo(1);
        assertThat(events()).isEmpty();

    }

    // 지워질 표마다 한 행 이상 두어, 지운 뒤 0 이 된 것이 원래 없어서가 아님을 보인다
    private Long cardWithEveryChild(UUID publicId) {

        Long cardId = fixtures.insertCard(publicId, myUserId);
        Long turnId = fixtures.insertInterviewTurn(cardId, 1, "T");
        jdbc.update("INSERT INTO interview_option (turn_id, seq, option_text) VALUES (?, 1, '보기')", turnId);
        Long statementId = fixtures.insertStatement(cardId, 1, "S", "상황", "COMMIT", "HIGH", turnId);
        fixtures.insertStatementEvidence(statementId, commitId);
        fixtures.insertSlotNote(cardId, 1, "R", "NO_EVIDENCE", null);

        return cardId;

    }

    private Map<String, Integer> rowsOf(Long cardId) {

        return Map.of(
                "card_version", count("SELECT count(*) FROM card_version WHERE card_id = ?", cardId),
                "card_statement", count("SELECT count(*) FROM card_statement WHERE card_id = ?", cardId),
                "statement_evidence", count("""
                        SELECT count(*) FROM statement_evidence e
                        JOIN card_statement s ON s.id = e.statement_id WHERE s.card_id = ?""", cardId),
                "card_slot_note", count("SELECT count(*) FROM card_slot_note WHERE card_id = ?", cardId),
                "interview_turn", count("SELECT count(*) FROM interview_turn WHERE card_id = ?", cardId),
                "interview_option", count("""
                        SELECT count(*) FROM interview_option o
                        JOIN interview_turn t ON t.id = o.turn_id WHERE t.card_id = ?""", cardId));

    }

    private int count(String sql, Long cardId) {

        return jdbc.queryForObject(sql, Integer.class, cardId);

    }

    private UUID candidateCard(Long candidateId, String jobState) {

        UUID jobId = candidates.insertDraftJob(myUserId, myRepoId, jobState);

        return candidates.cardPublicIdOf(candidates.insertCandidateCard(myUserId, myRepoId, candidateId, jobId, false));

    }

    private int cardCount(UUID publicId) {

        return jdbc.queryForObject("SELECT count(*) FROM card WHERE public_id = ?", Integer.class, publicId);

    }

    private ResultActions deleteCard(UUID publicId) throws Exception {

        return mvc.perform(delete("/api/cards/{id}", publicId).with(loggedInAs(myUserId)).with(csrf()));

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

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
