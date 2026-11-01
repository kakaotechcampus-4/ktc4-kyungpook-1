package com.gitory.backend.matching.api;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingContentStatus;
import com.gitory.backend.matching.domain.RecommendedCompany;
import com.gitory.backend.matching.domain.RepoRecommendation;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.RepoRecommendationRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
class RepoRecommendationApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobPostingRepository postings;

    @Autowired
    RepoRecommendationRepository recommendations;

    private TestFixtures fixtures;
    private Long myUserId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("내 레포의 저장된 추천을 상태·계산 시각·기업·대표 공고와 함께 돌려주고, 저장 뒤 마감된 공고는 마감으로 표시한다")
    void returnsSavedRecommendationWithClosedPostings() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        Long repo = fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "gitory"));
        JobPosting open = posting("daangn", "당근", "1", Instant.now().plusSeconds(86_400));
        JobPosting closed = posting("krafton", "크래프톤", "2", Instant.now().plusSeconds(86_400));
        jdbc.update("UPDATE job_posting SET status = 'EXPIRED' WHERE id = ?", closed.getId());
        Instant computedAt = Instant.parse("2026-10-31T00:00:00Z");
        recommendations.save(RepoRecommendation.ready(myUserId, repo, List.of(
                new RecommendedCompany("daangn", "당근", open.getId(), 2, 1, List.of("SPRING", "REDIS"), List.of(1L)),
                new RecommendedCompany("krafton", "크래프톤", closed.getId(), 1, 0, List.of("JAVA"), List.of(2L))),
                computedAt));

        mvc.perform(get("/api/repos/" + repoPublicId + "/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.computedAt").value("2026-10-31T00:00:00Z"))
                .andExpect(jsonPath("$.data.companies", hasSize(2)))
                .andExpect(jsonPath("$.data.companies[0].companyName").value("당근"))
                .andExpect(jsonPath("$.data.companies[0].logoUrl").isString())
                .andExpect(jsonPath("$.data.companies[0].posting.id").value(open.getPublicId().toString()))
                .andExpect(jsonPath("$.data.companies[0].posting.closed").value(false))
                .andExpect(jsonPath("$.data.companies[0].techs[1]").value("REDIS"))
                .andExpect(jsonPath("$.data.companies[0].requiredMet").value(2))
                .andExpect(jsonPath("$.data.companies[0].preferredMet").value(1))
                .andExpect(jsonPath("$.data.companies[1].posting.closed").value(true));

    }

    @Test
    @DisplayName("아직 계산한 적이 없는 레포는 NONE 과 빈 배열이다")
    void returnsNoneBeforeFirstCompute() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "gitory"));

        mvc.perform(get("/api/repos/" + repoPublicId + "/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.computedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.companies", hasSize(0)));

    }

    @Test
    @DisplayName("남의 레포와 UUID 가 아닌 id 는 없는 레포와 똑같이 404 이다")
    void hidesOthersRepositories() throws Exception {

        Long other = fixtures.insertUser(2L, "taehun0208");
        UUID othersRepo = UUID.randomUUID();
        fixtures.insertUserRepository(othersRepo, other, fixtures.insertRepository(11L, "taehun0208", "blog"));

        mvc.perform(get("/api/repos/" + othersRepo + "/recommendations").with(loggedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/repos/not-a-uuid/recommendations").with(loggedIn()))
                .andExpect(status().isNotFound());

    }

    private JobPosting posting(String companyKey, String companyName, String externalId, Instant expiresAt) {

        JobPosting posting = JobPosting.discovered(companyKey, externalId, companyName, "서버 개발자",
                "https://jobs.example.com/" + externalId, null, expiresAt, Instant.now());
        posting.extracted(JobCategory.BACKEND, ExperienceLevel.UNSPECIFIED, null, null, List.<CardTech>of(), 2,
                JobPostingContentStatus.READY, "a".repeat(64), Instant.now());
        return postings.saveAndFlush(posting);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
