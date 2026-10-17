package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CardOwnershipService.class, CardVersionWriter.class, AuditLog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CardOwnershipServiceTest {

    private static final UUID MY_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHERS_CARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID UNKNOWN_CARD = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CardOwnershipService service;

    @Autowired
    CardVersionWriter writer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Long myUserId;
    private Long myCardId;
    private Long othersCardId;

    // 거절 기록은 요청과 따로 커밋되므로 롤백되는 테스트 트랜잭션 없이 돌리고, 이전 테스트가 커밋한 행은 직접 지운다
    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        myUserId = fixtures.insertUser(1L, "grow22");
        Long othersUserId = fixtures.insertUser(2L, "taehun0208");
        myCardId = fixtures.insertCard(MY_CARD, myUserId);
        // 남의 카드 id 가 그 사용자 id 와 같아지면 기록에 어느 id 를 남겼는지 가려지지 않아 카드를 하나 더 만든다
        fixtures.insertCard(UUID.randomUUID(), myUserId);
        othersCardId = fixtures.insertCard(OTHERS_CARD, othersUserId);

    }

    @Test
    @DisplayName("내 카드는 찾기와 잠가 찾기 모두 그 카드를 돌려주고 기록을 남기지 않는다")
    void findsMyCard() {

        Card found = service.findOwned(MY_CARD, myUserId, AuditAction.VIEW_CARD);
        Card locked = inTransaction(() -> service.lockOwned(MY_CARD, myUserId, AuditAction.EDIT_CARD));

        assertThat(found.getId()).isEqualTo(myCardId);
        assertThat(locked.getId()).isEqualTo(myCardId);
        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("남의 카드를 가리키면 CardNotFoundException 이고 요청자·넘겨받은 action·그 카드 내부 id 로 DENIED 기록을 한 건씩 남긴다")
    void deniesOthersCard() {

        assertThatThrownBy(() -> service.findOwned(OTHERS_CARD, myUserId, AuditAction.VIEW_CARD))
                .isInstanceOf(CardNotFoundException.class);
        assertThat(events()).containsExactly(new AuditRow(myUserId, "VIEW_CARD", othersCardId, "DENIED"));

        assertThatThrownBy(() -> inTransaction(() -> service.lockOwned(OTHERS_CARD, myUserId, AuditAction.CONFIRM_CARD)))
                .isInstanceOf(CardNotFoundException.class);
        assertThat(events()).containsExactly(
                new AuditRow(myUserId, "VIEW_CARD", othersCardId, "DENIED"),
                new AuditRow(myUserId, "CONFIRM_CARD", othersCardId, "DENIED"));

    }

    @Test
    @DisplayName("없는 공개 id 면 찾기와 잠가 찾기 모두 CardNotFoundException 이고 기록을 남기지 않는다")
    void unknownCardIsNotRecorded() {

        assertThatThrownBy(() -> service.findOwned(UNKNOWN_CARD, myUserId, AuditAction.VIEW_CARD))
                .isInstanceOf(CardNotFoundException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.lockOwned(UNKNOWN_CARD, myUserId, AuditAction.EDIT_CARD)))
                .isInstanceOf(CardNotFoundException.class);

        assertThat(events()).isEmpty();

    }

    @Test
    @DisplayName("잠가 찾은 카드는 그 트랜잭션이 끝날 때까지 다른 요청이 잠가 찾을 수 없다")
    void lockedCardBlocksOtherLock() throws Exception {

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();

        try {
            Future<Card> holding = holder.submit(() -> inTransaction(() -> {
                Card card = service.lockOwned(MY_CARD, myUserId, AuditAction.EDIT_CARD);
                locked.countDown();
                awaitQuietly(release);
                return card;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> inTransaction(() -> {
                jdbc.execute("SET LOCAL lock_timeout = '300ms'");
                return service.lockOwned(MY_CARD, myUserId, AuditAction.EDIT_CARD);
            })).isInstanceOf(PessimisticLockingFailureException.class);

            release.countDown();
            assertThat(holding.get(10, TimeUnit.SECONDS).getId()).isEqualTo(myCardId);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

    }

    @Test
    @DisplayName("버전을 쓰는 메서드와 카드 잠가 찾기는 트랜잭션 밖에서 부르면 IllegalTransactionStateException 이다")
    void writesNeedTransaction() {

        Card card = Card.startManual(myUserId, null, "카드", null, null);

        assertThatThrownBy(() -> writer.startFirst(card, VersionSource.USER_EDIT))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> writer.startNext(card, VersionSource.USER_EDIT))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> writer.copyToNext(card, (short) 1, VersionSource.RESTORE))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> writer.copySlot(myCardId, (short) 1, StarField.S, (short) 2))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.lockOwned(MY_CARD, myUserId, AuditAction.EDIT_CARD))
                .isInstanceOf(IllegalTransactionStateException.class);

    }

    private <T> T inTransaction(Supplier<T> work) {

        return new TransactionTemplate(transactionManager).execute(status -> work.get());

    }

    private static void awaitQuietly(CountDownLatch latch) {

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private List<AuditRow> events() {

        return jdbc.query("SELECT user_id, action, subject_id, outcome FROM audit_event ORDER BY id",
                (row, rowNum) -> new AuditRow(row.getObject("user_id", Long.class), row.getString("action"),
                        row.getObject("subject_id", Long.class), row.getString("outcome")));

    }

    private record AuditRow(Long userId, String action, Long subjectId, String outcome) {
    }
}
