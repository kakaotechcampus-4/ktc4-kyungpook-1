package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.port.CardGenerationPort;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.recommend.domain.CandidateReferenceService;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CardDetailService.class, CardOwnershipService.class, CardVersionReader.class,
        RepositoryReferenceService.class, AuditLog.class, CandidateReferenceService.class, CommitSummaryService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CardDetailServiceTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CardDetailService service;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    CardGenerationPort generationJobs;

    private TestFixtures fixtures;
    private Long userId;
    private Long oldCommit;
    private Long newCommit;

    // 상세 읽기가 다른 트랜잭션의 커밋을 보는지 확인해야 하므로, 테스트 트랜잭션에 합류하지 않게 돌리고 행은 직접 지운다
    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        oldCommit = fixtures.insertCommit(repositoryId, "1111111111111111111111111111111111111111");
        newCommit = fixtures.insertCommit(repositoryId, "2222222222222222222222222222222222222222");

    }

    @Test
    @DisplayName("상세를 읽는 사이 다른 요청이 그 버전 문장을 지우고 다시 넣어 커밋해도, 먼저 읽은 문장과 그 문장의 근거 커밋을 한 시점으로 돌려준다")
    void readsStatementAndEvidenceAtOnePointInTime() throws Exception {

        Long cardId = fixtures.insertCard(MY_CARD, userId);
        Long statement = fixtures.insertStatement(cardId, 1, "S", "옛 상황", "COMMIT", "HIGH");
        fixtures.insertStatementEvidence(statement, oldCommit);
        ExecutorService reader = Executors.newSingleThreadExecutor();

        try {
            // 근거 표를 잠가 상세가 문장까지 읽고 근거를 읽기 직전에 멈춘 사이에, 임시 저장처럼 문장을 지우고 다시 넣는다
            Future<CardDetail> loading = transaction.execute(status -> {
                jdbc.execute("LOCK TABLE statement_evidence IN ACCESS EXCLUSIVE MODE");
                Future<CardDetail> load = reader.submit(() -> service.load(MY_CARD, userId));
                awaitBlockedRead();
                jdbc.update("DELETE FROM card_statement WHERE card_id = ? AND version_no = 1", cardId);
                Long rewritten = fixtures.insertStatement(cardId, 1, "S", "새 상황", "COMMIT", "HIGH");
                fixtures.insertStatementEvidence(rewritten, newCommit);
                return load;
            });

            assertThat(loading.get(10, TimeUnit.SECONDS).slots().slots().get(StarField.S)).isEqualTo(
                    new SlotContent(StarField.S, "옛 상황", EvidenceType.COMMIT, Confidence.HIGH, null,
                            List.of(oldCommit)));
        } finally {
            reader.shutdownNow();
        }

    }

    private void awaitBlockedRead() {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) == 0) {
            assertThat(System.nanoTime()).as("상세 읽기가 근거 표 잠금을 기다리기 시작해야 한다").isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }

    }
}
