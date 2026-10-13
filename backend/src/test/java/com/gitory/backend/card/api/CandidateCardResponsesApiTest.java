package com.gitory.backend.card.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.TestFixtures;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

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
class CandidateCardResponsesApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;
    private CandidateCardFixtures candidateFixtures;

    private Long myUserId;
    private Long myRepoId;
    private Long prCandidate;
    private Long clusterCandidate;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidateFixtures = new CandidateCardFixtures(jdbc);

        myUserId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        prCandidate = candidateFixtures.insertCandidate(myRepoId, "PR", 42, "#42", "이메일 발송", "USED");
        clusterCandidate = candidateFixtures.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "2026-05-12", "서버 구축", "USED");

    }

    @Test
    @DisplayName("가리기·버전 되돌리기·다시 열기 응답에도 카드 상세와 같은 후보와 초안 상태가 담긴다")
    void otherCardResponsesCarryCandidateAndGeneration() throws Exception {

        UUID cardId = candidateCard(prCandidate, "RUNNING", false);
        UUID jobId = generationJobOf(cardId);

        mvc.perform(post("/api/cards/{id}/mask", cardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\":[]}")
                        .with(loggedInAs(myUserId))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.ref").value("#42"))
                .andExpect(jsonPath("$.data.generation.jobId").value(jobId.toString()));
        mvc.perform(post("/api/cards/{id}/versions/{no}/restore", cardId, 1)
                        .with(loggedInAs(myUserId))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.ref").value("#42"))
                .andExpect(jsonPath("$.data.generation.jobId").value(jobId.toString()));

        UUID confirmedCard = candidateCard(clusterCandidate, "RUNNING", false);
        Long confirmedId = cardIdOf(confirmedCard);
        fixtures.insertCardVersion(confirmedId, 2, "USER_EDIT", true);
        fixtures.confirmCard(confirmedId);
        mvc.perform(post("/api/cards/{id}/reopen", confirmedCard).with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.ref").value("2026-05-12"))
                .andExpect(jsonPath("$.data.generation.jobId").value(generationJobOf(confirmedCard).toString()));

    }

    private UUID candidateCard(Long candidateId, String jobState, boolean partial) {

        UUID jobId = candidateFixtures.insertDraftJob(myUserId, myRepoId, jobState);

        return candidateFixtures.cardPublicIdOf(
                candidateFixtures.insertCandidateCard(myUserId, myRepoId, candidateId, jobId, partial));

    }

    private UUID generationJobOf(UUID cardId) {

        return jdbc.queryForObject("SELECT generation_job_id FROM card WHERE public_id = ?", UUID.class, cardId);

    }

    private Long cardIdOf(UUID cardId) {

        return jdbc.queryForObject("SELECT id FROM card WHERE public_id = ?", Long.class, cardId);

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
