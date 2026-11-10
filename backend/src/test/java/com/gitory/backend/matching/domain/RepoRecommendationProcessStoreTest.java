package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.RepoRecommendationRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class RepoRecommendationProcessStoreTest {

    private static final Instant DEADLINE = Instant.parse("2026-12-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RepoRecommendationService service;

    @Autowired
    RepoRecommendationQueryService query;

    @Autowired
    RepoRecommendationLiveLog liveLog;

    @Autowired
    RepoRecommendationRepository recommendations;

    @Autowired
    JobPostingRepository postings;

    @Autowired
    JobPostingCriterionRepository criteria;

    @MockitoBean
    MatchingCardReader cards;

    @MockitoBean
    CompanyShortlistService shortlist;

    @MockitoBean
    MatchJudgementService judgements;

    private Long userId;
    private Long repo;
    private UUID repoPublicId;
    private JobPosting posting;
    private JobPostingCriterion criterion;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        userId = fixtures.insertUser(1L, "grow22");
        repoPublicId = UUID.randomUUID();
        repo = fixtures.insertUserRepository(repoPublicId, userId, fixtures.insertRepository(10L, "grow22", "gitory"));
        posting = postings.saveAndFlush(JobPosting.discovered("daangn", "1", "당근", "서버 개발자",
                "https://jobs.example.com/1", null, DEADLINE, Instant.now()));
        criterion = criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(), CriterionType.REQUIRED, 1,
                "Spring 경험", "Spring 경험", null, List.of(CardTech.SPRING), null, false));
        MatchingCard card = new MatchingCard(11L, repo, "결제 API", null, null, "Spring 으로 결제 API 를 만들었다",
                null, List.of(CardTech.SPRING));
        when(cards.allOf(userId, repo)).thenReturn(List.of(card));
        when(shortlist.shortlist(eq(userId), any(), anyInt())).thenReturn(List.of(new ShortlistedCompany("daangn",
                "당근", posting, new TechOverlap(List.of("SPRING"), 1, List.of(), List.of()), 1)));
        when(judgements.judge(any(), anyList(), anyList())).thenReturn(
                Optional.of(Map.of(criterion.getId(), List.of(new JudgedSupport(11L, "Spring 으로 만들었다")))));

    }

    @Test
    @DisplayName("고르기가 끝나면 끝난 과정을 기업과 함께 추천 줄에 저장한다")
    void storesTheFinishedProcessWithTheCompanies() {

        service.compute(userId, repo);

        RepoRecommendationLive stored = recommendations.findByUserRepositoryId(repo).orElseThrow().getProcess();
        assertThat(stored).isNotNull();
        assertThat(stored.finished()).isTrue();
        assertThat(stored.cardCount()).isEqualTo(1);
        assertThat(stored.techs()).containsExactly("SPRING");
        RepoRecommendationLive.Candidate candidate = stored.candidates().getFirst();
        assertThat(candidate.companyId()).isEqualTo("daangn");
        assertThat(candidate.state()).isEqualTo(RepoRecommendationLiveState.DONE);
        assertThat(candidate.failed()).isFalse();
        assertThat(candidate.posting().deadline()).isEqualTo(DEADLINE);
        assertThat(candidate.hits()).extracting(RepoRecommendationLive.Hit::type, RepoRecommendationLive.Hit::cardTitle)
                .containsExactly(tuple(CriterionType.REQUIRED, "결제 API"));

    }

    @Test
    @DisplayName("서버 메모리에 과정이 없으면 저장된 과정을 live 로 돌려준다")
    void returnsTheStoredProcessWhenMemoryHasNone() {

        service.compute(userId, repo);
        RepoRecommendationLive stored = recommendations.findByUserRepositoryId(repo).orElseThrow().getProcess();
        liveLog.drop(repo);

        assertThat(stored).isNotNull();
        assertThat(query.of(repoPublicId, userId).live()).isEqualTo(stored);

    }

    @Test
    @DisplayName("서버 메모리에 과정이 있으면 저장된 과정이 아니라 메모리의 과정을 돌려준다")
    void prefersTheProcessInMemoryOverTheStoredOne() {

        service.compute(userId, repo);
        assertThat(recommendations.findByUserRepositoryId(repo).orElseThrow().getProcess()).isNotNull();
        liveLog.drop(repo);
        RepoRecommendationLiveLog.Live running = liveLog.start(repo, 5, List.of("JAVA"), List.of());

        RepoRecommendationLive live = query.of(repoPublicId, userId).live();

        assertThat(live).isEqualTo(running.snapshot());
        assertThat(live.cardCount()).isEqualTo(5);

    }

    @Test
    @DisplayName("고르기가 실패해도 지난 성공한 고르기의 저장된 과정은 지우지 않는다")
    void keepsTheStoredProcessWhenALaterPickFails() {

        service.compute(userId, repo);
        RepoRecommendationLive before = recommendations.findByUserRepositoryId(repo).orElseThrow().getProcess();
        when(judgements.judge(any(), anyList(), anyList())).thenReturn(Optional.empty());

        service.compute(userId, repo);

        RepoRecommendation after = recommendations.findByUserRepositoryId(repo).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RepoRecommendationStatus.FAILED);
        assertThat(before).isNotNull();
        assertThat(after.getProcess()).isEqualTo(before);

    }

    @Test
    @DisplayName("과정을 저장하기 전에 만든 줄은 메모리에 과정이 없으면 live 가 null 이다")
    void returnsNullLiveForRowsSavedBeforeProcessWasStored() {

        recommendations.saveAndFlush(RepoRecommendation.ready(userId, repo, List.of(), Instant.now()));

        assertThat(query.of(repoPublicId, userId).live()).isNull();

    }
}
