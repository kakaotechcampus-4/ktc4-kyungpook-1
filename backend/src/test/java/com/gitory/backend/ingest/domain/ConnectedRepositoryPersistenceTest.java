package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.ConnectedRepositoryRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConnectedRepositoryPersistenceTest {

    private static final Instant COUNTED_AT = Instant.parse("2026-10-05T01:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ConnectedRepositoryRepository connections;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private Long userId;
    private Long connectionId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(100L, "grow22", "gitory");
        connectionId = fixtures.insertUserRepository(userId, repositoryId);

    }

    @Test
    @DisplayName("개수 저장이 행을 읽은 사이 분석 저장이 먼저 끝나도, 개수 저장이 분석 시각을 지우지 않는다")
    void countingKeepsAnalyzedAtSavedMeanwhile() {

        inTransaction(() -> {
            ConnectedRepository countingRow = connections.findById(connectionId).orElseThrow();
            inNewTransaction(() -> connections.findById(connectionId).orElseThrow().markAnalyzed());
            countingRow.recordCounts(197, 52, 58, 23, 11, COUNTED_AT);
        });

        Map<String, Object> row = row();
        assertThat(row.get("last_analyzed_at")).isNotNull();
        assertThat(row.get("commit_count")).isEqualTo(197);

    }

    @Test
    @DisplayName("분석 저장이 행을 읽은 사이 개수 저장이 먼저 끝나도, 분석 저장이 개수와 센 시각을 지우지 않는다")
    void analysisKeepsCountsSavedMeanwhile() {

        inTransaction(() -> {
            ConnectedRepository analysisRow = connections.findById(connectionId).orElseThrow();
            inNewTransaction(() -> connections.findById(connectionId).orElseThrow()
                    .recordCounts(197, 52, 58, 23, 11, COUNTED_AT));
            analysisRow.markAnalyzed();
        });

        Map<String, Object> row = row();
        assertThat(row.get("commit_count")).isEqualTo(197);
        assertThat(row.get("counted_at")).isNotNull();
        assertThat(row.get("last_analyzed_at")).isNotNull();

    }

    @Test
    @DisplayName("셀 저장소는 한 번도 안 센 저장소가 먼저 오고, 같은 쪽 안에서는 최근에 push 한 저장소가 먼저 온다")
    void ordersUncountedFirstThenRecentlyPushed() {

        Instant longAgo = Instant.parse("2026-09-01T00:00:00Z");
        Instant recently = Instant.parse("2026-10-07T00:00:00Z");
        connect(101L, recently, COUNTED_AT);
        connect(102L, longAgo, null);
        connect(103L, recently, null);
        connect(104L, longAgo, COUNTED_AT);
        connect(105L, recently.plusSeconds(60), COUNTED_AT);

        assertThat(connections.findUncounted(userId, List.of(101L, 102L, 103L, 104L, 105L)))
                .extracting(ContributionTarget::githubRepoId)
                .containsExactly(103L, 102L, 105L, 101L);

    }

    private void connect(long githubRepoId, Instant pushedAt, Instant countedAt) {

        TestFixtures fixtures = new TestFixtures(jdbc);
        Long repositoryId = fixtures.insertRepository(githubRepoId, "grow22", "repo-" + githubRepoId);
        jdbc.update("UPDATE repository SET github_pushed_at = ? WHERE id = ?", Timestamp.from(pushedAt), repositoryId);
        Long id = fixtures.insertUserRepository(userId, repositoryId);
        if (countedAt != null) {
            jdbc.update("UPDATE user_repository SET counted_at = ? WHERE id = ?", Timestamp.from(countedAt), id);
        }

    }

    private void inTransaction(Runnable work) {

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());

    }

    private void inNewTransaction(Runnable work) {

        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> work.run());

    }

    private Map<String, Object> row() {

        return jdbc.queryForMap("SELECT last_analyzed_at, commit_count, counted_at FROM user_repository WHERE id = ?",
                connectionId);

    }
}
