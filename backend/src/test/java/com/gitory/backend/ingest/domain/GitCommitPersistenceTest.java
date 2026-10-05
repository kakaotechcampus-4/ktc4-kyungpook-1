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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class GitCommitPersistenceTest {

    private static final String SHA = "abc1234567890abc1234567890abc1234567890a";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private Long repositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
    }

    @Test
    @DisplayName("커밋의 필드마다 맞는 값이 저장된다")
    void savesEveryField() {

        GitCommit commit = persist(null);

        em.clear();
        GitCommit found = em.find(GitCommit.class, commit.getId());

        assertThat(found.getSha()).isEqualTo(SHA);
        assertThat(found.getAuthorLogin()).isEqualTo("grow22");
        assertThat(found.getAuthorName()).isEqualTo("Grow");
        assertThat(found.getMessage()).isEqualTo("feat: 세션 인덱스 추가");
        assertThat(found.getAuthoredAt()).isEqualTo(Instant.parse("2026-09-20T09:00:00Z"));
        assertThat(found.getAdditions()).isEqualTo(11);
        assertThat(found.getDeletions()).isEqualTo(22);
        assertThat(found.getChangedFiles()).isEqualTo(33);
        assertThat(found.getParentCount()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("제외 사유가 있는 커밋만 제외로 표시된다")
    void marksExcludedFromReason() {

        assertThat(persist(null).isExcluded()).isFalse();
        assertThat(persist(ExclusionReason.BOT, "bbb1234567890abc1234567890abc1234567890a").isExcluded()).isTrue();
    }

    @Test
    @DisplayName("제외 사유 네 가지가 모두 저장된다")
    void savesEveryExclusionReason() {

        String prefix = "1234567890abc1234567890abc1234567890abc";

        for (ExclusionReason reason : ExclusionReason.values()) {
            GitCommit commit = persist(reason, reason.ordinal() + prefix);
            assertThat(commit.getExclusionReason()).isEqualTo(reason);
        }
    }

    @Test
    @DisplayName("제외로 표시하면서 사유를 비우면 저장되지 않는다")
    void rejectsExcludedWithoutReason() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO git_commit (repository_id, sha, is_excluded, exclusion_reason) VALUES (?, ?, TRUE, NULL)",
                repositoryId, SHA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 저장소에 같은 커밋을 두 번 저장할 수 없다")
    void rejectsDuplicateShaInSameRepository() {

        persist(null);

        assertThatThrownBy(() -> persist(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    private GitCommit persist(ExclusionReason reason) {
        return persist(reason, SHA);
    }

    private GitCommit persist(ExclusionReason reason, String sha) {

        GitCommit commit = GitCommit.collected(repositoryId, null, sha, "grow22", "Grow",
                "feat: 세션 인덱스 추가", Instant.parse("2026-09-20T09:00:00Z"),
                11, 22, 33, (short) 1, reason);

        em.persist(commit);
        em.flush();

        return commit;
    }
}
