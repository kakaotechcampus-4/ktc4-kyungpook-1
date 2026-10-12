package com.gitory.backend.recommend.domain;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CandidateCardQueryService;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.LastAnalyzedService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.job.infra.UsedCardAdapter;
import com.gitory.backend.support.CandidateBoardFixtures;
import com.gitory.backend.support.StoredActivityFixtures;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** 보드 읽기가 다른 트랜잭션의 커밋을 보는지 확인해야 하므로, 테스트 트랜잭션에 합류하지 않게 돌리고 행은 직접 지운다 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CandidateBoardService.class, RepositoryOwnershipService.class, RepositoryReferenceService.class,
        LastAnalyzedService.class, CommitSummaryService.class, UsedCardAdapter.class, CandidateCardQueryService.class,
        AuditLog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CandidateBoardServiceTest {

    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CandidateBoardService service;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    private CandidateBoardFixtures board;
    private StoredActivityFixtures activity;
    private Long userId;
    private Long repositoryId;
    private Long myRepoId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        board = new CandidateBoardFixtures(jdbc);
        activity = new StoredActivityFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, userId, repositoryId);

    }

    @Test
    @DisplayName("보드를 읽는 사이 다시 분석이 손대지 않은 후보를 새 묶음으로 바꿔 커밋해도, 먼저 읽은 후보를 그 후보의 커밋과 함께 한 시점으로 돌려준다")
    void readsCandidatesAndTheirCommitsAtOnePointInTime() throws Exception {

        Long oldCluster = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "옛 묶음", "0.9");
        board.insertCandidateCommit(oldCluster, activity.insertCommit(repositoryId, "1".repeat(40), Instant.now(), 1),
                true, "Old.java");
        ExecutorService reader = Executors.newSingleThreadExecutor();

        try {
            // 후보 커밋 표를 잠가 보드가 후보까지 읽고 후보 커밋을 읽기 직전에 멈춘 사이에, 다시 분석처럼 후보를 지우고 새로 넣는다
            Future<CandidateBoard> loading = transaction.execute(status -> {
                jdbc.execute("LOCK TABLE candidate_commit IN ACCESS EXCLUSIVE MODE");
                Future<CandidateBoard> load = reader.submit(() -> service.load(MY_REPO, userId));
                awaitBlockedRead();
                jdbc.update("DELETE FROM candidate WHERE id = ?", oldCluster);
                Long newCluster = board.insertCandidate(myRepoId, "COMMIT_CLUSTER", null, "NEW", "새 묶음", "0.9");
                board.insertCandidateCommit(newCluster,
                        activity.insertCommit(repositoryId, "2".repeat(40), Instant.now(), 1), true, "New.java");
                return load;
            });

            CandidateBoard loaded = loading.get(10, TimeUnit.SECONDS);
            assertThat(loaded.candidates()).extracting(BoardCandidate::title).containsExactly("옛 묶음");
            assertThat(loaded.candidates().getFirst().commits()).extracting(BoardCommit::sha)
                    .containsExactly("1".repeat(40));
        } finally {
            reader.shutdownNow();
        }

    }

    @Test
    @DisplayName("보드를 읽는 사이 그 후보의 카드가 지워져 커밋돼도, 먼저 읽은 후보와 같은 시점의 카드 id 를 돌려준다")
    void readsCandidatesAndTheirCardsAtOnePointInTime() throws Exception {

        UUID cardId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        Long used = board.insertCandidate(myRepoId, "PR", 1, "USED", "쓴 후보", "0.9");
        board.insertCardOf(used, userId, cardId, false);
        ExecutorService reader = Executors.newSingleThreadExecutor();

        try {
            // 카드 표를 잠가 보드가 후보까지 읽고 카드를 읽기 직전에 멈춘 사이에 그 카드를 지운다
            Future<CandidateBoard> loading = transaction.execute(status -> {
                jdbc.execute("LOCK TABLE card IN ACCESS EXCLUSIVE MODE");
                Future<CandidateBoard> load = reader.submit(() -> service.load(MY_REPO, userId));
                awaitBlockedRead();
                jdbc.update("UPDATE card SET deleted_at = now() WHERE candidate_id = ?", used);
                return load;
            });

            assertThat(loading.get(10, TimeUnit.SECONDS).candidates().getFirst().usedByCardId()).isEqualTo(cardId);
        } finally {
            reader.shutdownNow();
        }

    }

    private void awaitBlockedRead() {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) == 0) {
            assertThat(System.nanoTime()).as("보드 읽기가 표 잠금을 기다리기 시작해야 한다").isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }

    }
}
