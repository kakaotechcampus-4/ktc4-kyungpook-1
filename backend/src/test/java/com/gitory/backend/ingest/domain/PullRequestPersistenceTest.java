package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
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
class PullRequestPersistenceTest {

    private static final Instant OPENED_AT = Instant.parse("2026-09-20T10:00:00Z");

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
    @DisplayName("PR 의 필드마다 맞는 값이 저장된다")
    void savesEveryField() {

        PullRequest saved = persist(17, GithubState.CLOSED);

        em.clear();
        PullRequest found = em.find(PullRequest.class, saved.getId());

        assertThat(found.getGithubPrNumber()).isEqualTo(17);
        assertThat(found.getTitle()).isEqualTo("세션 조회 성능 개선");
        assertThat(found.getBodyExcerpt()).isEqualTo("인덱스를 추가합니다. Resolves #42");
        assertThat(found.getState()).isEqualTo(GithubState.CLOSED);
        assertThat(found.getAuthorLogin()).isEqualTo("grow22");
        assertThat(found.getBaseBranch()).isEqualTo("develop");
        assertThat(found.getHeadBranch()).isEqualTo("feature/session-index");
        assertThat(found.getOpenedAt()).isEqualTo(OPENED_AT);
        assertThat(found.getMergedAt()).isEqualTo(Instant.parse("2026-09-21T11:00:00Z"));
    }

    @Test
    @DisplayName("커밋 sha 목록과 이슈 번호 목록이 배열 칸에 저장된다")
    void savesArrayColumns() {

        PullRequest saved = persist(17, GithubState.CLOSED);

        em.clear();
        PullRequest found = em.find(PullRequest.class, saved.getId());

        assertThat(found.getCommitShas()).containsExactly("abc1234567890", "def1234567890");
        assertThat(found.getLinkedIssueNumbers()).containsExactly(42, 43);
    }

    @Test
    @DisplayName("PR 상태 두 가지가 모두 저장된다")
    void savesEveryState() {

        assertThat(persist(1, GithubState.OPEN).getState()).isEqualTo(GithubState.OPEN);
        assertThat(persist(2, GithubState.CLOSED).getState()).isEqualTo(GithubState.CLOSED);
    }

    @Test
    @DisplayName("같은 저장소에 같은 PR 번호를 두 번 저장할 수 없다")
    void rejectsDuplicateNumberInSameRepository() {

        persist(17, GithubState.OPEN);

        assertThatThrownBy(() -> persist(17, GithubState.OPEN))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("OPEN 과 CLOSED 가 아닌 상태는 저장되지 않는다")
    void rejectsUnknownState() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO pull_request (repository_id, github_pr_number, title, state, base_branch, head_branch, opened_at)"
                        + " VALUES (?, 99, '제목', 'MERGED', 'develop', 'feature/x', now())",
                repositoryId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PullRequest persist(int number, GithubState state) {

        PullRequest pr = PullRequest.collected(repositoryId, null, number, "세션 조회 성능 개선",
                "인덱스를 추가합니다. Resolves #42", state, "grow22", "develop", "feature/session-index",
                OPENED_AT, Instant.parse("2026-09-21T11:00:00Z"),
                List.of("abc1234567890", "def1234567890"), List.of(42, 43));

        em.persist(pr);
        em.flush();

        return pr;
    }
}
