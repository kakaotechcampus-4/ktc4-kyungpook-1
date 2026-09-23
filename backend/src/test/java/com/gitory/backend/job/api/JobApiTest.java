package com.gitory.backend.job.api;

import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.job.domain.AnalysisJob;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class JobApiTest {

    private static final String KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AnalysisJobRepository jobs;

    private TestFixtures fixtures;

    private Long myUserId;
    private Long myRepoId;
    private Long othersUserId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "someone-else");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");

        myRepoId = fixtures.insertUserRepository(myUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(othersUserId, repositoryId);
    }

    @Test
    @DisplayName("내 Job 을 조회하면 계약대로 값이 내려온다")
    void returnsMyJob() throws Exception {

        UUID jobId = newJob(myUserId, myRepoId);

        getJob(jobId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.data.type").value("ANALYZE"))
                .andExpect(jsonPath("$.data.state").value("QUEUED"))
                .andExpect(jsonPath("$.data.partial").value(false))
                .andExpect(jsonPath("$.data.errorCode").value(nullValue()))
                .andExpect(jsonPath("$.data.retryable").value(nullValue()))
                .andExpect(jsonPath("$.data.retryAfterSec").value(nullValue()))
                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.result").value(nullValue()))
                .andExpect(jsonPath("$.data.startedAt").isString())
                .andExpect(jsonPath("$.data.updatedAt").isString())
                .andExpect(jsonPath("$.data.pollAfterMs").value(2000));
    }

    @Test
    @DisplayName("값이 비어 있어도 프론트가 요구하는 키 13개는 모두 내려간다")
    void keepsEveryContractKey() throws Exception {

        UUID jobId = newJob(myUserId, myRepoId);

        Map<String, Object> data = JsonPath.read(bodyOf(getJob(jobId)), "$.data");

        assertThat(data).containsOnlyKeys(
                "jobId", "type", "state", "partial", "steps", "errorCode", "retryable",
                "retryAfterSec", "startedAt", "updatedAt", "finishedAt", "result", "pollAfterMs");
    }

    @Test
    @DisplayName("단계 4개가 계약 순서대로 내려온다")
    void returnsFourStepsInOrder() throws Exception {

        UUID jobId = newJob(myUserId, myRepoId);

        getJob(jobId)
                .andExpect(jsonPath("$.data.steps.length()").value(4))
                .andExpect(jsonPath("$.data.steps[0].key").value("COMMITS"))
                .andExpect(jsonPath("$.data.steps[1].key").value("PR_REVIEW"))
                .andExpect(jsonPath("$.data.steps[2].key").value("COMPRESS"))
                .andExpect(jsonPath("$.data.steps[3].key").value("REASON"))
                .andExpect(jsonPath("$.data.steps[0].state").value("QUEUED"))
                .andExpect(jsonPath("$.data.steps[0].done").value(0))
                .andExpect(jsonPath("$.data.steps[0].total").value(nullValue()));
    }

    @Test
    @DisplayName("남의 Job 을 조회하면 없는 Job 과 똑같이 404 다")
    void othersJobIsNotFound() throws Exception {

        UUID jobId = newJob(othersUserId, othersRepoId);

        getJob(jobId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 Job 을 조회하면 404 에러 코드로 처리된다")
    void unknownJobIsNotFound() throws Exception {

        getJob(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("Job id 가 UUID 형식이 아니어도 500 이 아니라 404 에러다")
    void malformedJobIdIsNotFound() throws Exception {

        mvc.perform(get("/api/jobs/{id}", "not-a-uuid").with(loggedInAs(myUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("로그인하지 않은 Job 조회는 401 에러 코드로 처리된다")
    void requiresLogin() throws Exception {

        UUID jobId = newJob(myUserId, myRepoId);

        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    private UUID newJob(Long userId, Long userRepositoryId) {
        return jobs.save(AnalysisJob.enqueue(userId, userRepositoryId, KEY)).getPublicId();
    }

    private ResultActions getJob(UUID publicId) throws Exception {
        return mvc.perform(get("/api/jobs/{id}", publicId).with(loggedInAs(myUserId)));
    }

    private String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private RequestPostProcessor loggedInAs(Long userId) {
        LoginUser principal = new LoginUser(userId, "grow22", null);
        return authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
