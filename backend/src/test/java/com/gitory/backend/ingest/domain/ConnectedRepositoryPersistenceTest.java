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

import java.time.Instant;
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

    private Long connectionId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        Long userId = fixtures.insertUser(1L, "grow22");
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
