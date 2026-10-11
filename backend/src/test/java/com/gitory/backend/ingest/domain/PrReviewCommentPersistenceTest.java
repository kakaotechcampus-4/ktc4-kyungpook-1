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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PrReviewCommentPersistenceTest {

    // 실제 GitHub 댓글·리뷰 id 처럼 INT 를 넘는 값
    private static final long COMMENT_ID = 5_417_303_906L;
    private static final long REVIEW_ID = 5_417_303_905L;
    private static final Instant COMMENTED_AT = Instant.parse("2026-09-20T11:50:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private Long repositoryId;
    private Long runId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        Long userId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);

        runId = jdbc.queryForObject("INSERT INTO collection_run (user_repository_id) VALUES (?) RETURNING id",
                Long.class, userRepositoryId);
        jdbc.update("INSERT INTO pull_request (repository_id, github_pr_number, title, state, base_branch, head_branch,"
                + " opened_at) VALUES (?, 17, '세션 조회 성능 개선', 'OPEN', 'develop', 'feature/session-index', now())",
                repositoryId);

    }

    @Test
    @DisplayName("줄 댓글의 칸마다 맞는 값이 저장되고 INT 를 넘는 GitHub 댓글 id·리뷰 id 도 그대로 남는다")
    void savesEveryField() {

        PrReviewComment saved = persist(COMMENT_ID, 17);

        em.clear();
        PrReviewComment found = em.find(PrReviewComment.class, saved.getId());

        assertThat(found.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(found.getGithubPrNumber()).isEqualTo(17);
        assertThat(found.getCollectionRunId()).isEqualTo(runId);
        assertThat(found.getGithubCommentId()).isEqualTo(COMMENT_ID);
        assertThat(found.getGithubReviewId()).isEqualTo(REVIEW_ID);
        assertThat(found.getAuthorLogin()).isEqualTo("reviewer");
        assertThat(found.getBodyExcerpt()).isEqualTo("인덱스 책임을 repository 계층으로 옮겨주세요.");
        assertThat(found.getPath()).isEqualTo("backend/session.sql");
        assertThat(found.getLine()).isEqualTo(12);
        assertThat(found.getCommentedAt()).isEqualTo(COMMENTED_AT);

    }

    @Test
    @DisplayName("리뷰 id·작성자·파일·줄이 없는 줄 댓글도 그 칸을 비운 채 저장된다")
    void savesCommentWithoutOptionalValues() {

        PrReviewComment comment = PrReviewComment.collected(repositoryId, 17, null, COMMENT_ID, null, null,
                "본문만 있는 댓글", null, null, COMMENTED_AT);
        em.persist(comment);
        em.flush();

        em.clear();
        PrReviewComment found = em.find(PrReviewComment.class, comment.getId());

        assertThat(found.getCollectionRunId()).isNull();
        assertThat(found.getGithubReviewId()).isNull();
        assertThat(found.getAuthorLogin()).isNull();
        assertThat(found.getPath()).isNull();
        assertThat(found.getLine()).isNull();

    }

    @Test
    @DisplayName("pr_review 에 저장되지 않은 리뷰 id 를 가진 줄 댓글도 저장된다")
    void savesCommentOfReviewNotStored() {

        persist(COMMENT_ID, 17);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pr_review", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT github_review_id FROM pr_review_comment WHERE github_comment_id = ?",
                Long.class, COMMENT_ID)).isEqualTo(REVIEW_ID);

    }

    @Test
    @DisplayName("본문이 없는 줄 댓글은 저장되지 않는다")
    void rejectsCommentWithoutBody() {

        PrReviewComment comment = PrReviewComment.collected(repositoryId, 17, runId, COMMENT_ID, REVIEW_ID, "reviewer",
                null, "backend/session.sql", 12, COMMENTED_AT);

        assertThatThrownBy(() -> {
            em.persist(comment);
            em.flush();
        }).isInstanceOf(ConstraintViolationException.class);

    }

    @Test
    @DisplayName("작성 시각이 없는 줄 댓글은 저장되지 않는다")
    void rejectsCommentWithoutCommentedAt() {

        PrReviewComment comment = PrReviewComment.collected(repositoryId, 17, runId, COMMENT_ID, REVIEW_ID, "reviewer",
                "인덱스 책임을 repository 계층으로 옮겨주세요.", "backend/session.sql", 12, null);

        assertThatThrownBy(() -> {
            em.persist(comment);
            em.flush();
        }).isInstanceOf(ConstraintViolationException.class);

    }

    @Test
    @DisplayName("같은 GitHub 댓글 id 는 두 번 저장할 수 없다")
    void rejectsDuplicateGithubCommentId() {

        persist(COMMENT_ID, 17);

        assertThatThrownBy(() -> persist(COMMENT_ID, 17))
                .isInstanceOf(ConstraintViolationException.class);

    }

    @Test
    @DisplayName("저장되지 않은 PR 의 줄 댓글은 저장할 수 없다")
    void rejectsCommentOfPullRequestNotStored() {

        assertThatThrownBy(() -> persist(COMMENT_ID, 18))
                .isInstanceOf(ConstraintViolationException.class);

    }

    @Test
    @DisplayName("PR 이 지워지면 그 PR 의 줄 댓글도 같이 지워진다")
    void deletesCommentWithPullRequest() {

        persist(COMMENT_ID, 17);

        jdbc.update("DELETE FROM pull_request WHERE repository_id = ? AND github_pr_number = 17", repositoryId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pr_review_comment", Integer.class)).isZero();

    }

    @Test
    @DisplayName("수집 이력이 지워지면 줄 댓글은 남고 수집 이력 칸만 비워진다")
    void keepsCommentWhenCollectionRunDeleted() {

        persist(COMMENT_ID, 17);

        jdbc.update("DELETE FROM collection_run WHERE id = ?", runId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pr_review_comment", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT collection_run_id FROM pr_review_comment WHERE github_comment_id = ?",
                Long.class, COMMENT_ID)).isNull();

    }

    private PrReviewComment persist(long githubCommentId, int prNumber) {

        PrReviewComment comment = PrReviewComment.collected(repositoryId, prNumber, runId, githubCommentId, REVIEW_ID,
                "reviewer", "인덱스 책임을 repository 계층으로 옮겨주세요.", "backend/session.sql", 12, COMMENTED_AT);

        em.persist(comment);
        em.flush();

        return comment;

    }
}
