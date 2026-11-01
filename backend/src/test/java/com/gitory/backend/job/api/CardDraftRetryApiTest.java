package com.gitory.backend.job.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
class CardDraftRetryApiTest {

    private static final UUID MY_REPO = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Long myUserId;
    private Long othersUserId;
    private String cardId;

    @BeforeEach
    void setUp() throws Exception {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        CandidateCardFixtures candidates = new CandidateCardFixtures(jdbc);
        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long commitId = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        Long myRepoId = fixtures.insertUserRepository(MY_REPO, myUserId, repositoryId);
        Long candidate = candidates.insertCandidate(myRepoId, "PR", 42, "#42", "이메일 발송", "NEW");
        candidates.insertCandidateCommit(candidate, commitId, true);

        String created = mvc.perform(post("/api/repos/{id}/cards", MY_REPO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateIds\":[\"" + candidates.publicIdOf(candidate) + "\"]}")
                        .with(loggedInAs(myUserId)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        cardId = JsonPath.<List<String>>read(created, "$.data.cardIds").getFirst();
        // 초안 Job 이 끝나 AI 가 잠시 응답하지 못한 것으로 남은 상태를 만든다
        jdbc.update("UPDATE analysis_job SET state = 'FAILED', error_code = 'AI_UNAVAILABLE', finished_at = now()");
        jdbc.update("UPDATE card SET generation_failure = 'AI_UNAVAILABLE' WHERE public_id = ?::uuid", cardId);

    }

    @Test
    @DisplayName("초안 실패가 남은 카드로 다시 시도하면 새 초안 Job 을 QUEUED 로 만들고 카드를 그 Job 에 이어 실패 이유를 지운다")
    void retryQueuesNewDraftJobForTheCard() throws Exception {

        String body = retry(cardId, myUserId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String jobId = JsonPath.read(body, "$.data.jobId");
        Map<String, Object> card = jdbc.queryForMap(
                "SELECT generation_job_id::text AS job, generation_failure FROM card WHERE public_id = ?::uuid", cardId);
        assertThat(card.get("job")).isEqualTo(jobId);
        assertThat(card.get("generation_failure")).isNull();
        assertThat(jdbc.queryForMap("SELECT type, state FROM analysis_job WHERE public_id = ?::uuid", jobId))
                .containsEntry("type", "DRAFT").containsEntry("state", "QUEUED");

    }

    @Test
    @DisplayName("카드 상세는 초안 실패 이유를 draftFailure 로 돌려준다")
    void cardDetailShowsDraftFailure() throws Exception {

        mvc.perform(get("/api/cards/{id}", cardId).with(loggedInAs(myUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftFailure").value("AI_UNAVAILABLE"));

    }

    @Test
    @DisplayName("초안 실패가 없는 카드는 409 INVALID_STATE 이고 Job 을 만들지 않는다")
    void cardWithoutFailureIsRejected() throws Exception {

        jdbc.update("UPDATE card SET generation_failure = NULL");

        retry(cardId, myUserId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class)).isEqualTo(1);

    }

    @Test
    @DisplayName("되묻기·직접 수정으로 1번 버전을 지난 카드는 초안을 다시 담을 수 없어 409 INVALID_STATE 다")
    void cardPastFirstVersionIsRejected() throws Exception {

        jdbc.update("UPDATE card SET current_version = 2");

        retry(cardId, myUserId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

    }

    @Test
    @DisplayName("남의 카드나 잘못된 id 는 404 이고 카드를 바꾸지 않는다")
    void othersCardIsNotFound() throws Exception {

        retry(cardId, othersUserId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        retry("not-a-uuid", myUserId).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT generation_failure FROM card", String.class)).isEqualTo("AI_UNAVAILABLE");

    }

    private ResultActions retry(String id, Long userId) throws Exception {

        return mvc.perform(post("/api/cards/{id}/draft/retry", id).with(loggedInAs(userId)).with(csrf()));

    }

    private RequestPostProcessor loggedInAs(Long userId) {

        LoginUser principal = new LoginUser(userId, "grow22", null);

        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
