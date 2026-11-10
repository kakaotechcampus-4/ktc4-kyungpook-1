package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.OverallRecommendationRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class OverallRecommendationStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");
    private static final Duration STUCK = OverallRecommendation.STUCK_AFTER;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OverallRecommendationRepository recommendations;

    private Long userId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE overall_recommendation");
        userId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("계산 중인 사용자는 다시 시작하지 않고, 끝났거나 시작한 지 10분이 지난 줄만 확인한 수를 0 으로 되돌려 다시 시작한다")
    void startsOnceUntilItEndsOrGetsStuck() {

        assertThat(recommendations.start(userId, T0, T0.minus(STUCK))).isEqualTo(1);
        assertThat(recommendations.start(userId, T0.plusSeconds(60), T0.plusSeconds(60).minus(STUCK))).isZero();

        recommendations.progress(userId, 3, 15);
        jdbc.update("UPDATE overall_recommendation SET status = 'READY' WHERE user_id = ?", userId);
        Instant t1 = T0.plusSeconds(120);
        assertThat(recommendations.start(userId, t1, t1.minus(STUCK))).isEqualTo(1);
        assertThat(row()).containsEntry("status", "COMPUTING").containsEntry("progress_done", 0)
                .containsEntry("progress_total", 0);

        Instant beforeStuck = t1.plus(STUCK).minusSeconds(1);
        assertThat(recommendations.start(userId, beforeStuck, beforeStuck.minus(STUCK))).isZero();
        Instant stuck = t1.plus(STUCK);
        assertThat(recommendations.start(userId, stuck, stuck.minus(STUCK))).isEqualTo(1);

    }

    @Test
    @DisplayName("확인한 공고 수는 계산 중일 때 늘어날 때만 쓰여, 늦게 끝난 공고가 앞 숫자로 덮지 않는다")
    void progressOnlyMovesForwardWhileComputing() {

        recommendations.start(userId, T0, T0.minus(STUCK));
        recommendations.progress(userId, 0, 15);
        recommendations.progress(userId, 3, 15);
        recommendations.progress(userId, 2, 15);
        assertThat(row()).containsEntry("progress_done", 3).containsEntry("progress_total", 15);

        jdbc.update("UPDATE overall_recommendation SET status = 'READY' WHERE user_id = ?", userId);
        recommendations.progress(userId, 5, 15);
        assertThat(row()).containsEntry("progress_done", 3);

    }

    @Test
    @DisplayName("회사마다 맞은·모자란 조건 이름을 JSON 에 저장하고 그대로 다시 읽는다")
    void keepsConditionNamesThroughJson() {

        recommendations.start(userId, T0, T0.minus(STUCK));
        OverallRecommendation saved = recommendations.findByUserId(userId).orElseThrow();
        OverallCompany company = new OverallCompany("toss", "토스", 21L, 3, 4, 1, 2,
                List.of(new OverallCompany.Condition("Spring 서버 개발 경험", List.of("SPRING")),
                        new OverallCompany.Condition("코드 리뷰 문화 경험", List.of())),
                List.of(new OverallCompany.Condition("Kafka 운영 경험", List.of("KAFKA"))));
        saved.refresh(List.of(company), "fingerprint", T0);
        recommendations.save(saved);

        assertThat(recommendations.findByUserId(userId).orElseThrow().getCompanies()).containsExactly(company);

    }

    @Test
    @DisplayName("조건 이름 칸이 생기기 전에 저장한 회사는 빈 목록으로 읽힌다")
    void readsCompaniesSavedWithoutConditionNamesAsEmptyLists() {

        recommendations.start(userId, T0, T0.minus(STUCK));
        jdbc.update("UPDATE overall_recommendation SET companies = CAST(? AS jsonb) WHERE user_id = ?", """
                [{"companyId": "toss", "companyName": "토스", "postingId": 21, "requiredMet": 3, "requiredTotal": 4,
                  "preferredMet": 1, "preferredTotal": 2}]
                """, userId);

        OverallCompany company = recommendations.findByUserId(userId).orElseThrow().getCompanies().getFirst();
        assertThat(company.met()).isEmpty();
        assertThat(company.missing()).isEmpty();

    }

    private Map<String, Object> row() {

        return jdbc.queryForMap("SELECT status, progress_done, progress_total FROM overall_recommendation WHERE user_id = ?",
                userId);

    }
}
