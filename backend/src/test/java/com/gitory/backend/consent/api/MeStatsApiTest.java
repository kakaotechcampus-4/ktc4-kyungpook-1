package com.gitory.backend.consent.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.consent.infra.TokenCipher;
import com.gitory.backend.support.CandidateFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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
class MeStatsApiTest {

    private static final Instant EARLY = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-05T09:00:00Z");
    private static final Instant LATER_THAN_MINE = Instant.parse("2026-10-09T09:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TokenCipher cipher;

    private TestFixtures fixtures;
    private CandidateFixtures candidateFixtures;
    private Long myUserId;
    private Long otherUserId;
    private Long sharedRepositoryId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        candidateFixtures = new CandidateFixtures(jdbc);
        fixtures.clear();
        myUserId = fixtures.insertUser(1L, "grow22");
        otherUserId = fixtures.insertUser(2L, "other");
        sharedRepositoryId = fixtures.insertRepository(100L, "team", "shared");

    }

    @Test
    @DisplayName("확정한 카드는 내 CONFIRMED 카드만 세고 작성 중인 카드와 삭제된 확정 카드는 세지 않는다")
    void countsOnlyMyLiveConfirmedCards() throws Exception {

        confirmedCard(myUserId);
        confirmedCard(myUserId);
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        deleteCard(confirmedCard(myUserId));

        me().andExpect(jsonPath("$.data.stats.confirmedCards").value(2));

    }

    @Test
    @DisplayName("답한 질문은 작성 중이든 확정이든 내 카드의 ANSWERED 질문을 세고 열린·나중에·건너뜀·부족 질문과 삭제된 카드의 질문은 세지 않는다")
    void countsAnsweredTurnsOfMyLiveCards() throws Exception {

        Long draft = fixtures.insertCard(UUID.randomUUID(), myUserId);
        Long confirmed = confirmedCard(myUserId);
        Long deleted = confirmedCard(myUserId);
        turn(draft, 1, "ANSWERED");
        turn(draft, 2, "ANSWERED");
        turn(confirmed, 1, "ANSWERED");
        turn(confirmed, 2, null);
        turn(confirmed, 3, "LATER");
        turn(confirmed, 4, "SKIPPED");
        turn(confirmed, 5, "INSUFFICIENT");
        turn(deleted, 1, "ANSWERED");
        deleteCard(deleted);

        me().andExpect(jsonPath("$.data.stats.interviewTurns").value(3));

    }

    @Test
    @DisplayName("남은 후보는 내 저장소 연결의 NEW 후보만 세고 USED·EXCLUDED 후보는 세지 않는다")
    void countsOnlyNewCandidates() throws Exception {

        Long mine = fixtures.insertUserRepository(myUserId, sharedRepositoryId);
        Long secondRepository = fixtures.insertRepository(101L, "team", "second");
        Long mineToo = fixtures.insertUserRepository(myUserId, secondRepository);
        candidateFixtures.insertCandidate(mine, "COMMIT_CLUSTER", null, "NEW");
        candidateFixtures.insertCandidate(mine, "COMMIT_CLUSTER", null, "NEW");
        candidateFixtures.insertCandidate(mine, "COMMIT_CLUSTER", null, "USED");
        candidateFixtures.insertCandidate(mineToo, "COMMIT_CLUSTER", null, "NEW");
        candidateFixtures.insertCandidate(mineToo, "COMMIT_CLUSTER", null, "EXCLUDED");

        me().andExpect(jsonPath("$.data.stats.remainingCandidates").value(3));

    }

    @Test
    @DisplayName("저장소를 연결하지 않았으면 남은 후보와 분석한 저장소와 마지막 수집 시각은 0 과 null 이다")
    void withoutConnectedRepositoriesTheRepositoryNumbersAreEmpty() throws Exception {

        Long otherConnection = fixtures.insertUserRepository(otherUserId, sharedRepositoryId);
        candidateFixtures.insertCandidate(otherConnection, "COMMIT_CLUSTER", null, "NEW");
        analyzed(otherConnection, LATE);

        me().andExpect(jsonPath("$.data.stats.remainingCandidates").value(0))
                .andExpect(jsonPath("$.data.stats.analyzedRepos").value(0))
                .andExpect(jsonPath("$.data.github.lastCollectedAt").value(nullValue()));

    }

    @Test
    @DisplayName("분석한 저장소는 마지막 분석 시각이 있는 내 연결만 세고 분석하지 않은 연결은 세지 않는다")
    void countsOnlyAnalyzedRepositories() throws Exception {

        Long first = fixtures.insertUserRepository(myUserId, sharedRepositoryId);
        Long secondRepository = fixtures.insertRepository(101L, "team", "second");
        Long second = fixtures.insertUserRepository(myUserId, secondRepository);
        fixtures.insertUserRepository(myUserId, fixtures.insertRepository(102L, "team", "third"));
        analyzed(first, EARLY);
        analyzed(second, LATE);

        me().andExpect(jsonPath("$.data.stats.analyzedRepos").value(2));

    }

    @Test
    @DisplayName("마지막 수집 시각은 내 연결 중 가장 늦은 분석 시각이고 같은 저장소를 연결한 다른 사용자의 더 늦은 시각은 무시한다")
    void lastCollectedAtIsTheLatestOfMyRepositories() throws Exception {

        Long first = fixtures.insertUserRepository(myUserId, sharedRepositoryId);
        Long second = fixtures.insertUserRepository(myUserId, fixtures.insertRepository(101L, "team", "second"));
        Long others = fixtures.insertUserRepository(otherUserId, sharedRepositoryId);
        analyzed(first, EARLY);
        analyzed(second, LATE);
        analyzed(others, LATER_THAN_MINE);

        me().andExpect(jsonPath("$.data.github.lastCollectedAt").value(LATE.toString()));

    }

    @Test
    @DisplayName("분석한 저장소가 없으면 마지막 수집 시각은 null 이다")
    void lastCollectedAtIsNullWhenNothingWasAnalyzed() throws Exception {

        fixtures.insertUserRepository(myUserId, sharedRepositoryId);

        me().andExpect(jsonPath("$.data.github.lastCollectedAt").value(nullValue()));

    }

    @Test
    @DisplayName("마지막 수집 시각은 GitHub 연결이 없어도 있어도 채워진다")
    void lastCollectedAtIsFilledWithAndWithoutAGithubConnection() throws Exception {

        analyzed(fixtures.insertUserRepository(myUserId, sharedRepositoryId), LATE);

        me().andExpect(jsonPath("$.data.github.connected").value(false))
                .andExpect(jsonPath("$.data.github.lastCollectedAt").value(LATE.toString()));

        connect();

        me().andExpect(jsonPath("$.data.github.connected").value(true))
                .andExpect(jsonPath("$.data.github.lastCollectedAt").value(LATE.toString()));

    }

    @Test
    @DisplayName("다른 사용자의 카드와 질문과 후보와 분석 시각은 같은 저장소를 연결해도 내 숫자에 들어오지 않는다")
    void anotherUsersRowsNeverCount() throws Exception {

        Long mine = fixtures.insertUserRepository(myUserId, sharedRepositoryId);
        analyzed(mine, EARLY);
        candidateFixtures.insertCandidate(mine, "COMMIT_CLUSTER", null, "NEW");
        Long othersConnection = fixtures.insertUserRepository(otherUserId, sharedRepositoryId);
        analyzed(othersConnection, LATE);
        candidateFixtures.insertCandidate(othersConnection, "COMMIT_CLUSTER", null, "NEW");
        Long othersCard = confirmedCard(otherUserId);
        turn(othersCard, 1, "ANSWERED");

        me().andExpect(jsonPath("$.data.stats.confirmedCards").value(0))
                .andExpect(jsonPath("$.data.stats.analyzedRepos").value(1))
                .andExpect(jsonPath("$.data.stats.interviewTurns").value(0))
                .andExpect(jsonPath("$.data.stats.remainingCandidates").value(1))
                .andExpect(jsonPath("$.data.github.lastCollectedAt").value(EARLY.toString()));

    }

    @Test
    @DisplayName("내 정보 응답은 숫자 네 개와 마지막 수집 시각을 프론트 계약의 경로 그대로 채워 준다")
    void meCarriesAllFiveValues() throws Exception {

        Long mine = fixtures.insertUserRepository(myUserId, sharedRepositoryId);
        analyzed(mine, LATE);
        candidateFixtures.insertCandidate(mine, "COMMIT_CLUSTER", null, "NEW");
        Long card = confirmedCard(myUserId);
        turn(card, 1, "ANSWERED");

        me().andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.stats.confirmedCards").value(1))
                .andExpect(jsonPath("$.data.stats.analyzedRepos").value(1))
                .andExpect(jsonPath("$.data.stats.interviewTurns").value(1))
                .andExpect(jsonPath("$.data.stats.remainingCandidates").value(1))
                .andExpect(jsonPath("$.data.github.lastCollectedAt").value(LATE.toString()));

    }

    private Long confirmedCard(Long userId) {

        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", true);
        fixtures.confirmCard(cardId);

        return cardId;

    }

    private void deleteCard(Long cardId) {

        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", cardId);

    }

    private void turn(Long cardId, int seq, String outcome) {

        Long turnId = fixtures.insertInterviewTurn(cardId, seq, "S");
        if (outcome == null) {
            return;
        }
        jdbc.update("UPDATE interview_turn SET outcome = ?, answer_text = '답', answer_source = 'TYPED' WHERE id = ?",
                outcome, turnId);

    }

    private void analyzed(Long userRepositoryId, Instant at) {

        jdbc.update("UPDATE user_repository SET last_analyzed_at = ? WHERE id = ?", Timestamp.from(at), userRepositoryId);

    }

    private void connect() {

        jdbc.update("INSERT INTO github_connection (user_id, scopes, token_enc) VALUES (?, ARRAY['read:user'], ?)",
                myUserId, cipher.encrypt("gho_StubbedAccessTokenExample1234567"));

    }

    private ResultActions me() throws Exception {

        return mvc.perform(get("/api/me").with(loggedIn()));

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
