package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.port.CardGenerationPort;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ManualCardService.class, CardDetailService.class, CardOwnershipService.class, CardVersionWriter.class,
        CardVersionReader.class, RepositoryOwnershipService.class, RepositoryReferenceService.class, AuditLog.class,
        CandidateReferenceService.class, CommitSummaryService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManualCardServiceTest {

    private static final String KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ManualCardService service;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    CardGenerationPort generationJobs;

    private TestFixtures fixtures;
    private Long userId;

    // 서비스가 스스로 연 트랜잭션을 커밋해야 다른 요청이 그 카드를 보므로, 롤백되는 테스트 트랜잭션 없이 돌리고 행은 직접 지운다
    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("같은 키의 카드가 아직 커밋되지 않았을 때 온 요청은 유니크 인덱스에서 기다렸다가, 새로 만들지 않고 커밋된 그 카드를 돌려준다")
    void returnsCardOfSameKeyCommittedWhileWaiting() throws Exception {

        UUID first = UUID.randomUUID();
        ExecutorService other = Executors.newSingleThreadExecutor();

        try {
            // 먼저 온 요청을 커밋하지 않은 채 두어, 나중 요청이 막힌 뒤 다시 찾는 길을 반드시 지나게 한다
            Future<CardDetail> later = transaction.execute(status -> {
                Long cardId = fixtures.insertCard(first, userId);
                jdbc.update("UPDATE card SET idempotency_key = ? WHERE id = ?", KEY, cardId);
                Future<CardDetail> started = other.submit(() -> service.start(userId, "나중 제목", null, null, KEY));
                awaitBlockedRequest();
                return started;
            });

            assertThat(later.get(10, TimeUnit.SECONDS).publicId()).isEqualTo(first);
        } finally {
            other.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM card")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM card_version")).isEqualTo(1);

    }

    @Test
    @DisplayName("같은 키로 동시에 8번 보내도 카드는 한 장만 생기고 모든 응답의 카드 id 가 같다")
    void concurrentSameKeyCreatesOneCard() throws Exception {

        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch startLine = new CountDownLatch(1);
        List<UUID> ids = new ArrayList<>();

        try {
            List<Future<CardDetail>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await();
                    return service.start(userId, "동시 제목", null, null, KEY);
                }));
            }
            startLine.countDown();

            for (Future<CardDetail> future : futures) {
                ids.add(future.get(10, TimeUnit.SECONDS).publicId());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ids).hasSize(requests).containsOnly(ids.get(0));
        assertThat(count("SELECT count(*) FROM card")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM card_version")).isEqualTo(1);

    }

    private void awaitBlockedRequest() {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count("SELECT count(*) FROM pg_locks WHERE NOT granted") == 0) {
            assertThat(System.nanoTime()).as("나중 요청이 잠금을 기다리기 시작해야 한다").isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }

    }

    private int count(String sql) {

        return jdbc.queryForObject(sql, Integer.class);

    }
}
