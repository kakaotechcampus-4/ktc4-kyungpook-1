package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CollectionRunPersistenceTest {

    private static final String HEAD_SHA = "abc1234567890abc1234567890abc1234567890a";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private Long userRepositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);

        Long userId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");

        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
    }

    @Test
    @DisplayName("수집 기록의 필드마다 맞는 값이 저장된다")
    void savesEveryField() {

        CollectionRun run = persist(null);

        em.clear();
        CollectionRun found = em.find(CollectionRun.class, run.getId());

        assertThat(found.getBranches()).containsExactly("develop", "feature/session-index");
        assertThat(found.getSince()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));
        assertThat(found.getHeadSha()).isEqualTo(HEAD_SHA);
        assertThat(found.getCommitsCollected()).isEqualTo(11);
        assertThat(found.getPrsCollected()).isEqualTo(22);
        assertThat(found.getIssuesCollected()).isEqualTo(33);
        assertThat(found.getCollectedAt()).isNotNull();
    }

    @Test
    @DisplayName("덜 읽은 사유가 있는 수집만 일부 수집으로 표시된다")
    void marksPartialFromReason() {

        assertThat(persist(null).isPartial()).isFalse();
        assertThat(persist(PartialReason.CAP_EXCEEDED).isPartial()).isTrue();
    }

    @Test
    @DisplayName("일부 수집으로 표시하면서 사유를 비우면 저장되지 않는다")
    void rejectsPartialWithoutReason() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO collection_run (user_repository_id, partial, partial_reason) VALUES (?, TRUE, NULL)",
                userRepositoryId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("덜 읽은 사유 세 가지가 모두 저장된다")
    void savesEveryPartialReason() {

        for (PartialReason reason : PartialReason.values()) {
            assertThat(persist(reason).getPartialReason()).isEqualTo(reason);
        }
    }

    private CollectionRun persist(PartialReason reason) {

        CollectionRun run = CollectionRun.recorded(userRepositoryId,
                List.of("develop", "feature/session-index"),
                Instant.parse("2026-09-28T00:00:00Z"), HEAD_SHA, 11, 22, 33, reason);

        em.persist(run);
        em.flush();

        return run;
    }
}
