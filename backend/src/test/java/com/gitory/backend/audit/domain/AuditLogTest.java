package com.gitory.backend.audit.domain;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AuditLog.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditLogTest {

    private static final Long SUBJECT_ID = 42L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    AuditLog auditLog;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    private TestFixtures fixtures;
    private Long userId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");

    }

    @Test
    @DisplayName("요청 트랜잭션이 롤백돼도 거절 기록은 남는다")
    void deniedSurvivesRollback() {

        transaction.executeWithoutResult(status -> {
            auditLog.denied(userId, AuditAction.VIEW_JOB, SUBJECT_ID);
            status.setRollbackOnly();
        });

        assertThat(outcomes()).containsExactly("DENIED");

    }

    @Test
    @DisplayName("요청 트랜잭션 안에서 남긴 성공 기록은 커밋된 뒤에 저장된다")
    void okIsSavedAfterCommit() {

        Integer countBeforeCommit = transaction.execute(status -> {
            auditLog.ok(userId, AuditAction.ANALYZE, SUBJECT_ID);
            return jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class);
        });

        assertThat(countBeforeCommit).isZero();
        assertThat(outcomes()).containsExactly("OK");

    }

    @Test
    @DisplayName("요청 트랜잭션이 롤백되면 성공 기록을 남기지 않는다")
    void okIsDroppedOnRollback() {

        transaction.executeWithoutResult(status -> {
            auditLog.ok(userId, AuditAction.ANALYZE, SUBJECT_ID);
            status.setRollbackOnly();
        });

        assertThat(outcomes()).isEmpty();

    }

    @Test
    @DisplayName("첫 로그인처럼 같은 트랜잭션에서 방금 만든 사용자의 성공 기록도 남는다")
    void okCanReferenceUserCreatedInSameTransaction() {

        Long newUserId = transaction.execute(status -> {
            Long created = fixtures.insertUser(2L, "first-login");
            auditLog.ok(created, AuditAction.CONNECT, SUBJECT_ID);
            return created;
        });

        assertThat(jdbc.queryForList("SELECT user_id FROM audit_event", Long.class)).containsExactly(newUserId);

    }

    @Test
    @DisplayName("기록에 실패해도 예외를 던지지 않아 요청은 그대로 진행된다")
    void failureIsNotThrown() {

        assertThatCode(() -> auditLog.denied(-1L, AuditAction.VIEW_JOB, SUBJECT_ID))
                .doesNotThrowAnyException();

        assertThat(outcomes()).isEmpty();

    }

    private List<String> outcomes() {

        return jdbc.queryForList("SELECT outcome FROM audit_event ORDER BY id", String.class);

    }
}
