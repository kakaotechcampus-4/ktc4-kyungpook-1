package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.FakeInterviewAi;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공유 계약 사례(contracts/interview/)의 카드를 DB 에 넣고 같은 요청을 보낸다
 * 카드·커밋 번호가 사례의 고정 번호여야 해서, 같은 표에 순번으로 행을 만드는 다른 테스트와 섞지 않으려고 이 클래스에서만 다룬다
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@AutoConfigureMockMvc
@Testcontainers
class InterviewAskContractTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final FakeInterviewAi AI = FakeInterviewAi.start();

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

    @BeforeEach
    void setUp() {

        jdbc.execute("TRUNCATE users, repository RESTART IDENTITY CASCADE");
        AI.reset();

    }

    @Test
    @DisplayName("계약 사례의 직접 작성 카드(T칸 비어 있음)에 같은 요청을 보내면 AI 에는 사례의 요청과 같은 JSON 을 보내고, 응답의 출처는 사례와 같다")
    void sendsWhatTheSharedCaseExpects() throws Exception {

        JsonNode contract = MAPPER.readTree(Path.of("../contracts/interview/direct-card.json"));
        insertGivenCard(contract.path("givenCard"));

        assertSharedCase(contract);

    }

    @Test
    @DisplayName("계약 사례의 PR 후보 카드(A칸에 근거 커밋 하나)에 같은 요청을 보내면 AI 에는 사례의 요청과 같은 JSON 을 보내고, 응답의 출처는 PR 이다")
    void sendsWhatTheSharedPrCandidateCaseExpects() throws Exception {

        JsonNode contract = MAPPER.readTree(Path.of("../contracts/interview/pr-candidate-card.json"));
        insertGivenCandidateCard(contract.path("givenCard"));

        assertSharedCase(contract);

    }

    private void assertSharedCase(JsonNode contract) throws Exception {

        JsonNode card = contract.path("givenCard");
        long userId = card.path("userId").longValue();
        long cardId = card.path("id").longValue();

        JsonNode frontend = contract.path("frontendToSpring").path("request");
        LoginUser principal = new LoginUser(userId, "grow22", null);
        mvc.perform(request(HttpMethod.valueOf(frontend.path("method").asString()), frontend.path("path").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(frontend.path("body")))
                        .with(authentication(new UsernamePasswordAuthenticationToken(principal, null,
                                principal.getAuthorities())))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceType")
                        .value(contract.path("frontendToSpring").path("expectedResponseFields").path("sourceType")
                                .asString()));

        assertThat(AI.requests()).hasSize(1);
        assertThat((Object) AI.requests().get(0)).isEqualTo(contract.path("springToAi").path("expectedRequest"));
        assertThat(AI.requests().get(0).path("card_id").longValue()).isEqualTo(cardId);

    }

    // 사례의 카드는 T칸만 비어 있고, 나머지 칸이 차 있어도 요청에는 T칸 하나만 실려야 한다
    private void insertGivenCard(JsonNode card) {

        jdbc.update("INSERT INTO users (id, github_user_id, github_login) OVERRIDING SYSTEM VALUE VALUES (?, 1, 'grow22')",
                card.path("userId").longValue());
        jdbc.update("INSERT INTO card (id, public_id, user_id, card_type, origin, title) OVERRIDING SYSTEM VALUE VALUES (?, ?::uuid, ?, 'QUALITATIVE', ?, '카드')",
                card.path("id").longValue(), card.path("publicId").asString(), card.path("userId").longValue(),
                card.path("origin").asString());
        jdbc.update("INSERT INTO card_version (card_id, version_no, source) VALUES (?, 1, 'USER_EDIT')",
                card.path("id").longValue());
        for (String slot : new String[]{"S", "A", "R"}) {
            jdbc.update("INSERT INTO card_statement (card_id, version_no, star_slot, seq, body, evidence_type) VALUES (?, 1, ?, 1, ?, 'USER_STATED')",
                    card.path("id").longValue(), slot, slot + " 칸에 이미 쓴 글");
        }

    }

    // 후보의 두 커밋은 모두 포함으로 두고, 칸 근거는 사례의 slotA 가 가리키는 커밋이다
    private void insertGivenCandidateCard(JsonNode card) {

        TestFixtures fixtures = new TestFixtures(jdbc);
        CandidateCardFixtures candidates = new CandidateCardFixtures(jdbc);
        long cardId = card.path("id").longValue();
        JsonNode candidate = card.path("candidate");
        JsonNode slot = card.path("slotA");

        jdbc.update("INSERT INTO users (id, github_user_id, github_login) OVERRIDING SYSTEM VALUE VALUES (?, 1, 'grow22')",
                card.path("userId").longValue());
        Long repositoryId = fixtures.insertRepository(100L, "grow22", "gitory");
        Long userRepositoryId = fixtures.insertUserRepository(card.path("userId").longValue(), repositoryId);
        for (JsonNode commit : card.path("commits")) {
            jdbc.update("INSERT INTO git_commit (id, repository_id, sha, message, additions, deletions) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?)",
                    commit.path("id").longValue(), repositoryId, commit.path("sha").asString(),
                    commit.path("message").asString(), commit.path("additions").intValue(),
                    commit.path("deletions").intValue());
        }
        Long candidateId = candidates.insertCandidate(userRepositoryId, candidate.path("sourceType").asString(),
                candidate.path("githubPrNumber").intValue(), "7", "세션 조회 개선", "USED");
        for (JsonNode commit : card.path("commits")) {
            candidates.insertCandidateCommit(candidateId, commit.path("id").longValue(), true);
        }
        jdbc.update("INSERT INTO card (id, public_id, user_id, user_repository_id, candidate_id, card_type, origin, title) OVERRIDING SYSTEM VALUE VALUES (?, ?::uuid, ?, ?, ?, 'TECH', ?, '후보 카드')",
                cardId, card.path("publicId").asString(), card.path("userId").longValue(), userRepositoryId, candidateId,
                card.path("origin").asString());
        jdbc.update("INSERT INTO card_version (card_id, version_no, source) VALUES (?, 1, 'USER_EDIT')", cardId);
        Long statementId = fixtures.insertStatement(cardId, 1, "A", slot.path("body").asString(),
                slot.path("evidenceType").asString(), slot.path("confidence").asString());
        for (JsonNode commitId : slot.path("evidenceCommitIds")) {
            fixtures.insertStatementEvidence(statementId, commitId.longValue());
        }

    }
}
