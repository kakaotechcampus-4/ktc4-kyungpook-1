package com.gitory.backend.matching.api;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.consent.domain.LoginUser;
import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.JobPosting;
import com.gitory.backend.matching.domain.JobPostingContentStatus;
import com.gitory.backend.matching.domain.OverallRecommendation;
import com.gitory.backend.matching.domain.OverallRecommendationService;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.OverallRecommendationRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
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
class OverallRecommendationApiTest {

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
    OverallRecommendationRepository recommendations;

    @MockitoBean
    OverallRecommendationService service;

    private Long myUserId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        jdbc.execute("TRUNCATE overall_recommendation");
        myUserId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("계산 중인 전체 추천을 상태·확인한 공고 수·지난 기업과 대표 공고·필수와 우대 조건 수로 돌려준다")
    void returnsComputingRecommendationInContractShape() throws Exception {

        JobPosting posting = posting("daangn", "당근");
        Instant now = Instant.now();
        recommendations.start(myUserId, now, now.minus(OverallRecommendation.STUCK_AFTER));
        recommendations.progress(myUserId, 6, 15);
        jdbc.update("""
                UPDATE overall_recommendation SET computed_at = '2026-10-31T00:00:00Z',
                    companies = CAST(? AS jsonb) WHERE user_id = ?
                """, """
                [{"companyId": "daangn", "companyName": "당근", "postingId": %d, "requiredMet": 3, "requiredTotal": 4,
                  "preferredMet": 1, "preferredTotal": 2}]
                """.formatted(posting.getId()), myUserId);

        mvc.perform(get("/api/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPUTING"))
                .andExpect(jsonPath("$.data.computedAt").value("2026-10-31T00:00:00Z"))
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.progress.done").value(6))
                .andExpect(jsonPath("$.data.progress.total").value(15))
                .andExpect(jsonPath("$.data.companies", hasSize(1)))
                .andExpect(jsonPath("$.data.companies[0].companyName").value("당근"))
                .andExpect(jsonPath("$.data.companies[0].logoUrl").isString())
                .andExpect(jsonPath("$.data.companies[0].posting.id").value(posting.getPublicId().toString()))
                .andExpect(jsonPath("$.data.companies[0].posting.closed").value(false))
                .andExpect(jsonPath("$.data.companies[0].requiredMet").value(3))
                .andExpect(jsonPath("$.data.companies[0].requiredTotal").value(4))
                .andExpect(jsonPath("$.data.companies[0].preferredMet").value(1))
                .andExpect(jsonPath("$.data.companies[0].preferredTotal").value(2));

    }

    @Test
    @DisplayName("계산을 마친 뒤 입력이 바뀌었으면 stale 이 true 이고 진행은 싣지 않는다")
    void returnsStaleWhenInputChangedAfterCompute() throws Exception {

        Instant now = Instant.now();
        recommendations.start(myUserId, now, now.minus(OverallRecommendation.STUCK_AFTER));
        jdbc.update("UPDATE overall_recommendation SET status = 'READY', computed_at = now(), fingerprint = 'old' "
                + "WHERE user_id = ?", myUserId);

        mvc.perform(get("/api/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.stale").value(true))
                .andExpect(jsonPath("$.data.progress").value(nullValue()));

    }

    @Test
    @DisplayName("한 번도 요청하지 않았으면 NONE·진행 없음·빈 배열이다")
    void returnsNoneBeforeFirstRequest() throws Exception {

        mvc.perform(get("/api/recommendations").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.computedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.progress").value(nullValue()))
                .andExpect(jsonPath("$.data.companies", hasSize(0)));

    }

    @Test
    @DisplayName("계산 시작은 로그인한 사용자로 시작하고 조회와 같은 모양을 돌려준다")
    void startsForTheLoggedInUserAndReturnsTheSameShape() throws Exception {

        mvc.perform(post("/api/recommendations").with(loggedIn()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"))
                .andExpect(jsonPath("$.data.companies", hasSize(0)));

        verify(service).start(myUserId);

    }

    private JobPosting posting(String companyKey, String companyName) {

        JobPosting posting = JobPosting.discovered(companyKey, "1", companyName, "서버 개발자",
                "https://jobs.example.com/" + companyKey, null, Instant.now().plusSeconds(86_400), Instant.now());
        posting.extracted(JobCategory.BACKEND, ExperienceLevel.UNSPECIFIED, null, null, List.<CardTech>of(), 2,
                JobPostingContentStatus.READY, "a".repeat(64), Instant.now());
        return postings.saveAndFlush(posting);

    }

    private RequestPostProcessor loggedIn() {

        LoginUser principal = new LoginUser(myUserId, "grow22", null);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

    }
}
