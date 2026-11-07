package com.gitory.backend.matching.domain;

import com.gitory.backend.matching.infra.MatchGapFillRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "gitory.consent.token-key=test-encryption-key",
        "gitory.consent.token-salt=5c0744940b5c369b"
})
@Testcontainers
class MatchGapFillStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Duration STUCK = MatchGapFill.STUCK_AFTER;
    private static final String WAITING = "[{\"conditionId\":\"0b7d3e19-4c2a-4f81-9d6e-a3c5f7b1e201\",\"decision\":null,"
            + "\"decisionReason\":null,\"steps\":[],\"proposal\":null,\"result\":null}]";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MatchGapFillRepository fills;

    @Autowired
    TransactionTemplate transaction;

    private Long userId;
    private Long postingId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        jdbc.execute("TRUNCATE job_posting CASCADE");
        userId = fixtures.insertUser(1L, "grow22");
        postingId = jdbc.queryForObject("""
                INSERT INTO job_posting (company_key, external_id, company_name, title, source_url, first_seen_at,
                    last_seen_at)
                VALUES ('narae', '1', '나래랩스', '서버 개발자', 'https://careers.example.com/1', now(), now())
                RETURNING id
                """, Long.class);

    }

    @Test
    @DisplayName("찾는 중인 공고는 다시 시작하지 않고, 끝났거나 시작한 지 5분이 지난 줄만 지문을 비우고 새 조건으로 다시 시작한다")
    void startsOnceUntilItEndsOrGetsStuck() {

        assertThat(fills.start(userId, postingId, WAITING, T0, T0.minus(STUCK))).isEqualTo(1);
        Instant t1 = T0.plusSeconds(60);
        assertThat(fills.start(userId, postingId, "[]", t1, t1.minus(STUCK))).isZero();
        assertThat(row()).containsEntry("status", "RUNNING");

        jdbc.update("UPDATE match_gap_fill SET status = 'DONE', fingerprint = 'f', finished_at = now()");
        assertThat(fills.start(userId, postingId, WAITING, t1, t1.minus(STUCK))).isEqualTo(1);
        assertThat(row()).containsEntry("status", "RUNNING").containsEntry("fingerprint", null)
                .containsEntry("finished_at", null);

        Instant beforeStuck = t1.plus(STUCK).minusSeconds(1);
        assertThat(fills.start(userId, postingId, "[]", beforeStuck, beforeStuck.minus(STUCK))).isZero();
        Instant stuck = t1.plus(STUCK);
        assertThat(fills.start(userId, postingId, "[]", stuck, stuck.minus(STUCK))).isEqualTo(1);
        assertThat(fills.findByUserIdAndJobPostingId(userId, postingId).orElseThrow().getConditions()).isEmpty();

    }

    @Test
    @DisplayName("끝낸 조건 상태는 단계·제안까지 저장했다가 그대로 다시 읽힌다")
    void storesConditionsWithStepsAndProposals() {

        fills.start(userId, postingId, WAITING, T0, T0.minus(STUCK));
        assertThat(fills.findByUserIdAndJobPostingId(userId, postingId).orElseThrow().getConditions())
                .containsExactly(GapFillCondition.waiting(UUID.fromString("0b7d3e19-4c2a-4f81-9d6e-a3c5f7b1e201")));
        UUID shop = UUID.randomUUID();
        List<GapFillCondition> filled = List.of(new GapFillCondition(UUID.randomUUID(), GapFillDecision.SEARCH,
                "관련 있음", false, List.of(new GapFillStep(GapFillStepKind.GITHUB_SEARCHED, 4, "kafka", null),
                new GapFillStep(GapFillStepKind.OUTSIDE_REPO_FOUND, null, null, "toss/slash")),
                new GapFillProposal(GapFillAction.ANALYZE_REPO, shop, "grow22/shop", "grow22/shop", shop,
                        "주문 큐 커밋이 있다"), GapFillResult.PROPOSED));

        transaction.executeWithoutResult(status -> fills.findWithLockByUserIdAndJobPostingId(userId, postingId)
                .orElseThrow().finish(filled, "a".repeat(64), T0.plusSeconds(30)));

        MatchGapFill saved = fills.findByUserIdAndJobPostingId(userId, postingId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(GapFillStatus.DONE);
        assertThat(saved.getConditions()).isEqualTo(filled);
        assertThat(saved.getFingerprint()).isEqualTo("a".repeat(64));

    }

    @Test
    @DisplayName("다시 찾기는 읽은 그 끝난 줄에서만 시작하고 지문은 그대로 두며, 찾는 중이거나 그사이 다시 시작한 줄이면 0 이다")
    void restartsOnlyTheFinishedRowItRead() {

        fills.start(userId, postingId, WAITING, T0, T0.minus(STUCK));
        Instant t1 = T0.plusSeconds(60);
        assertThat(fills.restart(userId, postingId, "[]", t1, T0)).isZero();

        jdbc.update("UPDATE match_gap_fill SET status = 'DONE', fingerprint = 'f', finished_at = now()");
        assertThat(fills.restart(userId, postingId, "[]", t1, T0.minusSeconds(1))).isZero();
        assertThat(fills.restart(userId, postingId, WAITING, t1, T0)).isEqualTo(1);
        assertThat(row()).containsEntry("status", "RUNNING").containsEntry("fingerprint", "f")
                .containsEntry("finished_at", null);
        assertThat(fills.findByUserIdAndJobPostingId(userId, postingId).orElseThrow().getRequestedAt()).isEqualTo(t1);

    }

    @Test
    @DisplayName("못 채운 조건이 없는 공고는 바로 빈 DONE 이 되지만, 찾는 중인 줄은 덮지 않는다")
    void finishesEmptyUnlessRunning() {

        fills.start(userId, postingId, WAITING, T0, T0.minus(STUCK));
        Instant t1 = T0.plusSeconds(60);
        fills.finishEmpty(userId, postingId, t1, t1.minus(STUCK));
        assertThat(row()).containsEntry("status", "RUNNING");

        jdbc.update("UPDATE match_gap_fill SET status = 'FAILED'");
        fills.finishEmpty(userId, postingId, t1, t1.minus(STUCK));
        assertThat(row()).containsEntry("status", "DONE").containsEntry("conditions", "[]");

    }

    private Map<String, Object> row() {

        return jdbc.queryForMap("""
                SELECT status, fingerprint, finished_at, conditions::text AS conditions
                FROM match_gap_fill WHERE user_id = ? AND job_posting_id = ?
                """, userId, postingId);

    }
}
