package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PrReviewPersistenceTest {

    // 실제 GitHub 리뷰 id 처럼 INT 를 넘는 값
    private static final long REVIEW_ID = 5_417_303_905L;
    private static final Instant SUBMITTED_AT = Instant.parse("2026-09-20T12:00:00Z");

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
    @DisplayName("리뷰의 칸마다 맞는 값이 저장되고 INT 를 넘는 GitHub 리뷰 id 도 그대로 남는다")
    void savesEveryField() {

        PrReview saved = persist(REVIEW_ID, 17, ReviewState.CHANGES_REQUESTED);

        em.clear();
        PrReview found = em.find(PrReview.class, saved.getId());

        assertThat(found.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(found.getGithubPrNumber()).isEqualTo(17);
        assertThat(found.getCollectionRunId()).isEqualTo(runId);
        assertThat(found.getGithubReviewId()).isEqualTo(REVIEW_ID);
        assertThat(found.getState()).isEqualTo(ReviewState.CHANGES_REQUESTED);
        assertThat(found.getAuthorLogin()).isEqualTo("reviewer");
        assertThat(found.getBodyExcerpt()).isEqualTo("책임 분리를 명확히 해주세요.");
        assertThat(found.getSubmittedAt()).isEqualTo(SUBMITTED_AT);

    }

    @ParameterizedTest
    @EnumSource(ReviewState.class)
    @DisplayName("리뷰 상태 네 가지가 모두 저장된다")
    void savesEveryState(ReviewState state) {

        PrReview saved = persist(REVIEW_ID, 17, state);

        em.clear();

        assertThat(em.find(PrReview.class, saved.getId()).getState()).isEqualTo(state);

    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RESOLVED"})
    @DisplayName("네 가지가 아닌 리뷰 상태는 제출하지 않은 PENDING 을 포함해 저장되지 않는다")
    void rejectsUnknownState(String state) {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO pr_review (repository_id, github_pr_number, github_review_id, state)"
                        + " VALUES (?, 17, 1, ?)",
                repositoryId, state))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("본문·제출 시각·작성자가 없는 리뷰도 그 칸을 비운 채 저장된다")
    void savesReviewWithoutOptionalValues() {

        PrReview review = PrReview.collected(repositoryId, 17, null, REVIEW_ID, ReviewState.COMMENTED, null, null,
                null);
        em.persist(review);
        em.flush();

        em.clear();
        PrReview found = em.find(PrReview.class, review.getId());

        assertThat(found.getCollectionRunId()).isNull();
        assertThat(found.getAuthorLogin()).isNull();
        assertThat(found.getBodyExcerpt()).isNull();
        assertThat(found.getSubmittedAt()).isNull();

    }

    @Test
    @DisplayName("같은 GitHub 리뷰 id 는 두 번 저장할 수 없다")
    void rejectsDuplicateGithubReviewId() {

        persist(REVIEW_ID, 17, ReviewState.APPROVED);

        assertThatThrownBy(() -> persist(REVIEW_ID, 17, ReviewState.APPROVED))
                .isInstanceOf(ConstraintViolationException.class);

    }

    @Test
    @DisplayName("저장되지 않은 PR 의 리뷰는 저장할 수 없다")
    void rejectsReviewOfPullRequestNotStored() {

        assertThatThrownBy(() -> persist(REVIEW_ID, 18, ReviewState.APPROVED))
                .isInstanceOf(ConstraintViolationException.class);

    }

    @Test
    @DisplayName("PR 이 지워지면 그 PR 의 리뷰도 같이 지워진다")
    void deletesReviewWithPullRequest() {

        persist(REVIEW_ID, 17, ReviewState.APPROVED);

        jdbc.update("DELETE FROM pull_request WHERE repository_id = ? AND github_pr_number = 17", repositoryId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pr_review", Integer.class)).isZero();

    }

    @Test
    @DisplayName("수집 이력이 지워지면 리뷰는 남고 수집 이력 칸만 비워진다")
    void keepsReviewWhenCollectionRunDeleted() {

        persist(REVIEW_ID, 17, ReviewState.APPROVED);

        jdbc.update("DELETE FROM collection_run WHERE id = ?", runId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pr_review", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT collection_run_id FROM pr_review WHERE github_review_id = ?",
                Long.class, REVIEW_ID)).isNull();

    }

    private PrReview persist(long githubReviewId, int prNumber, ReviewState state) {

        PrReview review = PrReview.collected(repositoryId, prNumber, runId, githubReviewId, state, "reviewer",
                "책임 분리를 명확히 해주세요.", SUBMITTED_AT);

        em.persist(review);
        em.flush();

        return review;

    }
}
