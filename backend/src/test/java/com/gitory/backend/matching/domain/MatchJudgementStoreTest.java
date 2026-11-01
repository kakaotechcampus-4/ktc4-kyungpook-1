package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.JudgedCondition;
import com.gitory.backend.agent.port.JudgedCondition.ConditionSupport;
import com.gitory.backend.agent.port.MatchJudgementPort;
import com.gitory.backend.agent.port.MatchJudgementRequest;
import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class MatchJudgementStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MatchJudgementService service;

    @Autowired
    JobPostingRepository postings;

    @Autowired
    JobPostingCriterionRepository criteria;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    MatchJudgementPort port;

    private JobPosting posting;
    private JobPostingCriterion spring;
    private JobPostingCriterion redis;
    private Long paymentCard;
    private Long cacheCard;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        Long userId = fixtures.insertUser(1L, "grow22");
        paymentCard = fixtures.insertCard(UUID.randomUUID(), userId);
        cacheCard = fixtures.insertCard(UUID.randomUUID(), userId);

        posting = JobPosting.discovered("acme", "1", "에이크미", "백엔드 개발자", "https://jobs.example.com/1", null, null,
                NOW);
        posting.extracted(JobCategory.BACKEND, ExperienceLevel.UNSPECIFIED, null, null, List.of(), 2,
                JobPostingContentStatus.READY, "a".repeat(64), NOW);
        postings.saveAndFlush(posting);
        spring = criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(), CriterionType.REQUIRED, 1,
                "Spring 으로 API 를 개발한 경험", "Spring", null, List.of(CardTech.SPRING), null, false));
        redis = criteria.saveAndFlush(JobPostingCriterion.extracted(posting.getId(), CriterionType.PREFERRED, 2,
                "Redis 로 캐시를 써 본 경험", "Redis", null, List.of(CardTech.REDIS), null, false));

    }

    @Test
    @DisplayName("같은 카드 글로 이미 판단한 짝은 AI 에게 다시 묻지 않고, 못 채운 짝도 저장해 다시 묻지 않는다")
    void reusesStoredJudgementsForTheSameCardText() {

        when(port.judge(any())).thenReturn(List.of(
                new JudgedCondition(String.valueOf(spring.getId()),
                        List.of(new ConditionSupport(String.valueOf(paymentCard), "결제 API 를 Spring 으로 만들었다"))),
                new JudgedCondition(String.valueOf(redis.getId()), List.of())));
        List<MatchingCard> cards = List.of(payment("Spring 으로 결제 API 를 만들었다"), cache("캐시로 조회를 줄였다"));

        Map<Long, List<JudgedSupport>> first = service.judge(posting, List.of(spring, redis), cards).orElseThrow();
        Map<Long, List<JudgedSupport>> second = service.judge(posting, List.of(spring, redis), cards).orElseThrow();

        verify(port, times(1)).judge(any());
        assertThat(second).isEqualTo(first);
        assertThat(second.get(spring.getId()))
                .containsExactly(new JudgedSupport(paymentCard, "결제 API 를 Spring 으로 만들었다"));
        assertThat(second.get(redis.getId())).isEmpty();

    }

    @Test
    @DisplayName("카드 글이 한 글자라도 바뀌면 그 카드만 다시 묻고, 옛 글의 판단은 지운다")
    void asksAgainOnlyForTheChangedCardAndDropsItsOldJudgements() {

        when(port.judge(any())).thenReturn(List.of(
                new JudgedCondition(String.valueOf(spring.getId()), List.of()),
                new JudgedCondition(String.valueOf(redis.getId()), List.of())));
        service.judge(posting, List.of(spring, redis), List.of(payment("결제 API"), cache("캐시")));
        clearInvocations(port);

        service.judge(posting, List.of(spring, redis), List.of(payment("결제 API"), cache("캐시 도입")));

        ArgumentCaptor<MatchJudgementRequest> asked = ArgumentCaptor.forClass(MatchJudgementRequest.class);
        verify(port).judge(asked.capture());
        assertThat(asked.getValue().cards()).extracting(card -> card.cardId())
                .containsExactly(String.valueOf(cacheCard));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT card_hash) FROM match_judgement WHERE card_id = ?",
                Integer.class, cacheCard)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match_judgement", Integer.class)).isEqualTo(4);

    }

    @Test
    @DisplayName("공고 조건이 다시 뽑혀 지워지면 그 조건의 판단도 지워진다")
    void deletesJudgementsWithTheirCriterion() {

        when(port.judge(any())).thenReturn(List.of(
                new JudgedCondition(String.valueOf(spring.getId()), List.of()),
                new JudgedCondition(String.valueOf(redis.getId()), List.of())));
        service.judge(posting, List.of(spring, redis), List.of(payment("결제 API")));

        jdbc.update("DELETE FROM job_posting_criterion WHERE job_posting_id = ?", posting.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM match_judgement", Integer.class)).isZero();

    }

    private MatchingCard payment(String action) {

        return new MatchingCard(paymentCard, null, "결제 API", null, null, action, null, List.of(CardTech.SPRING));

    }

    private MatchingCard cache(String result) {

        return new MatchingCard(cacheCard, null, "캐시 도입", null, null, null, result, List.of(CardTech.REDIS));

    }
}
