package com.gitory.backend.matching.api;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingContentStatus;
import com.gitory.backend.matching.domain.RecommendedCompany;
import com.gitory.backend.matching.domain.RepoRecommendation;
import com.gitory.backend.matching.domain.RepoRecommendationLive;
import com.gitory.backend.matching.domain.RepoRecommendationLiveLog;
import com.gitory.backend.matching.domain.RepoRecommendationLiveState;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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

    @Autowired
    RepoRecommendationLiveLog liveLog;

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
                .andExpect(jsonPath("$.data.companies[0].fresh").value(false))
                .andExpect(jsonPath("$.data.companies[1].posting.closed").value(true));

    }

    @Test
    @DisplayName("계산한 적도 카드도 없는 레포는 NONE 과 빈 배열이고, 다른 레포에 카드가 있어도 계산을 예약하지 않는다")
    void returnsNoneWithoutReservingWhenRepoHasNoCards() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "gitory"));
        cardIn(fixtures.insertUserRepository(myUserId, fixtures.insertRepository(11L, "grow22", "blog")));

        mvc.perform(get("/api/repos/" + repoPublicId + "/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.computedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.companies", hasSize(0)))
                .andExpect(jsonPath("$.data.queued").value(false))
                .andExpect(jsonPath("$.data.live").value(nullValue()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM repo_recommendation", Integer.class)).isZero();

    }

    @Test
    @DisplayName("계산한 적이 없는 레포에 카드가 있으면 조회할 때 계산을 예약하고 COMPUTING 과 queued 로 답한다")
    void reservesComputeWhenRepoHasCardsButNoRecommendation() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        Long repo = fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "gitory"));
        cardIn(repo);

        mvc.perform(get("/api/repos/" + repoPublicId + "/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPUTING"))
                .andExpect(jsonPath("$.data.computedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.companies", hasSize(0)))
                .andExpect(jsonPath("$.data.queued").value(true))
                .andExpect(jsonPath("$.data.live").value(nullValue()));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM repo_recommendation
                WHERE user_repository_id = ? AND status = 'COMPUTING' AND stale_since <= now() - interval '50 seconds'
                """, Integer.class, repo)).isEqualTo(1);

    }

    @Test
    @DisplayName("조회는 예약된 다시 계산을 queued 로, 남아 있는 진행을 live 로 싣는다")
    void returnsQueuedReservationAndLiveProgress() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        Long repo = fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "gitory"));
        UUID postingId = UUID.randomUUID();
        recommendations.markStale(myUserId, repo, Instant.now());
        liveLog.start(repo, 3, List.of("SPRING", "JPA"), List.of(
                new RepoRecommendationLive.Candidate("daangn", "당근", "https://static.example.com/daangn.png",
                        new RepoRecommendationLive.Posting(postingId, "서버 개발자", Instant.parse("2026-11-20T00:00:00Z")),
                        2, RepoRecommendationLiveState.DONE, false,
                        List.of(new RepoRecommendationLive.Hit(CriterionType.REQUIRED, "Spring 서버 개발 경험", "결제 API"))),
                RepoRecommendationLive.Candidate.waiting("krafton", "크래프톤", null,
                        new RepoRecommendationLive.Posting(UUID.randomUUID(), "게임 서버 개발자", null), 1)));

        mvc.perform(get("/api/repos/" + repoPublicId + "/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPUTING"))
                .andExpect(jsonPath("$.data.queued").value(true))
                .andExpect(jsonPath("$.data.live.cardCount").value(3))
                .andExpect(jsonPath("$.data.live.techs[1]").value("JPA"))
                .andExpect(jsonPath("$.data.live.finished").value(false))
                .andExpect(jsonPath("$.data.live.candidates", hasSize(2)))
                .andExpect(jsonPath("$.data.live.candidates[0].companyId").value("daangn"))
                .andExpect(jsonPath("$.data.live.candidates[0].companyName").value("당근"))
                .andExpect(jsonPath("$.data.live.candidates[0].logoUrl").value("https://static.example.com/daangn.png"))
                .andExpect(jsonPath("$.data.live.candidates[0].posting.id").value(postingId.toString()))
                .andExpect(jsonPath("$.data.live.candidates[0].posting.title").value("서버 개발자"))
                .andExpect(jsonPath("$.data.live.candidates[0].posting.deadline").value("2026-11-20T00:00:00Z"))
                .andExpect(jsonPath("$.data.live.candidates[0].overlap").value(2))
                .andExpect(jsonPath("$.data.live.candidates[0].state").value("DONE"))
                .andExpect(jsonPath("$.data.live.candidates[0].failed").value(false))
                .andExpect(jsonPath("$.data.live.candidates[0].hits[0].type").value("REQUIRED"))
                .andExpect(jsonPath("$.data.live.candidates[0].hits[0].text").value("Spring 서버 개발 경험"))
                .andExpect(jsonPath("$.data.live.candidates[0].hits[0].cardTitle").value("결제 API"))
                .andExpect(jsonPath("$.data.live.candidates[1].logoUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.live.candidates[1].posting.deadline").value(nullValue()))
                .andExpect(jsonPath("$.data.live.candidates[1].state").value("WAITING"))
                .andExpect(jsonPath("$.data.live.candidates[1].hits", hasSize(0)));

        recommendations.claim(recommendations.findByUserRepositoryId(repo).orElseThrow().getId(),
                Instant.now().plusSeconds(60));

        mvc.perform(get("/api/repos/" + repoPublicId + "/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPUTING"))
                .andExpect(jsonPath("$.data.queued").value(false))
                .andExpect(jsonPath("$.data.live.candidates", hasSize(2)));

    }

    @Test
    @DisplayName("남의 레포와 UUID 가 아닌 id 는 없는 레포와 똑같이 404 이다")
    void hidesOthersRepositories() throws Exception {

        Long other = fixtures.insertUser(2L, "taehun0208");
        UUID othersRepo = UUID.randomUUID();
        Long othersRepoId = fixtures.insertUserRepository(othersRepo, other,
                fixtures.insertRepository(11L, "taehun0208", "blog"));
        liveLog.start(othersRepoId, 1, List.of("JAVA"), List.of());

        mvc.perform(get("/api/repos/" + othersRepo + "/recommendations").with(loggedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/repos/not-a-uuid/recommendations").with(loggedIn()))
                .andExpect(status().isNotFound());

    }

    @Test
    @DisplayName("계산이 결과를 바꾸면 바뀌기 전 기업을 남겨, 바뀌기 전 결과에 없던 기업만 fresh 로 싣는다")
    void marksCompaniesNotInThePreviousResultAsFresh() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        Long repo = fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "gitory"));
        JobPosting kept = posting("daangn", "당근", "1", Instant.now().plusSeconds(86_400));
        JobPosting added = posting("krafton", "크래프톤", "2", Instant.now().plusSeconds(86_400));
        RecommendedCompany daangn = new RecommendedCompany("daangn", "당근", kept.getId(), 2, 1, List.of("SPRING"), List.of(1L));
        RecommendedCompany krafton = new RecommendedCompany("krafton", "크래프톤", added.getId(), 1, 0, List.of("JAVA"), List.of(2L));
        RepoRecommendation recommendation = RepoRecommendation.ready(myUserId, repo, List.of(daangn),
                Instant.parse("2026-10-31T00:00:00Z"));
        recommendation.refresh(List.of(daangn, krafton), Instant.parse("2026-11-01T00:00:00Z"));
        recommendations.save(recommendation);

        mvc.perform(get("/api/repos/" + repoPublicId + "/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companies[0].companyId").value("daangn"))
                .andExpect(jsonPath("$.data.companies[0].fresh").value(false))
                .andExpect(jsonPath("$.data.companies[1].companyId").value("krafton"))
                .andExpect(jsonPath("$.data.companies[1].fresh").value(true));

    }

    @Test
    @DisplayName("다시 고르기를 요청하면 본인 레포의 추천을 다음 차례에 바로 계산하도록 예약하고, 지난 추천을 그대로 보이며 queued 가 true 인 조회와 같은 응답을 준다")
    void refreshReservesImmediateRecomputeAndAnswersLikeTheLookup() throws Exception {

        UUID repoPublicId = UUID.randomUUID();
        Long repo = fixtures.insertUserRepository(repoPublicId, myUserId, fixtures.insertRepository(10L, "grow22", "gitory"));
        JobPosting open = posting("daangn", "당근", "1", Instant.now().plusSeconds(86_400));
        recommendations.save(RepoRecommendation.ready(myUserId, repo, List.of(
                new RecommendedCompany("daangn", "당근", open.getId(), 2, 1, List.of("SPRING"), List.of(1L))),
                Instant.parse("2026-10-31T00:00:00Z")));

        mvc.perform(post("/api/repos/" + repoPublicId + "/recommendations/refresh").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("COMPUTING"))
                .andExpect(jsonPath("$.data.queued").value(true))
                .andExpect(jsonPath("$.data.companies", hasSize(1)))
                .andExpect(jsonPath("$.data.companies[0].companyName").value("당근"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM repo_recommendation
                WHERE user_repository_id = ? AND status = 'COMPUTING' AND stale_since <= now() - interval '50 seconds'
                """, Integer.class, repo)).isEqualTo(1);

    }

    @Test
    @DisplayName("남의 레포와 UUID 가 아닌 id 로 다시 고르기를 요청하면 조회와 똑같이 404 이고 계산을 예약하지 않는다")
    void refreshHidesOthersRepositories() throws Exception {

        Long other = fixtures.insertUser(2L, "taehun0208");
        UUID othersRepo = UUID.randomUUID();
        fixtures.insertUserRepository(othersRepo, other, fixtures.insertRepository(11L, "taehun0208", "blog"));

        mvc.perform(post("/api/repos/" + othersRepo + "/recommendations/refresh").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(post("/api/repos/not-a-uuid/recommendations/refresh").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM repo_recommendation", Integer.class)).isZero();

    }

    private JobPosting posting(String companyKey, String companyName, String externalId, Instant expiresAt) {

        JobPosting posting = JobPosting.discovered(companyKey, externalId, companyName, "서버 개발자",
                "https://jobs.example.com/" + externalId, null, expiresAt, Instant.now());
        posting.extracted(JobCategory.BACKEND, ExperienceLevel.UNSPECIFIED, null, null, List.<CardTech>of(), 2,
                JobPostingContentStatus.READY, "a".repeat(64), Instant.now());
        return postings.saveAndFlush(posting);

    }

    // 글이 없어도 카드이므로, 이 레포에 묶은 작성 중 카드를 하나 만든다
    private void cardIn(Long userRepositoryId) {

        Long card = fixtures.insertCard(UUID.randomUUID(), myUserId);
        jdbc.update("UPDATE card SET user_repository_id = ? WHERE id = ?", userRepositoryId, card);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
