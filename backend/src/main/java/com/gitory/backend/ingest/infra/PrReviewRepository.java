package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.PrReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrReviewRepository extends JpaRepository<PrReview, Long> {

    // 다른 Job 이 같은 리뷰를 먼저 저장했어도 유니크 위반 없이 이번 수집 값으로 덮어쓴다
    // 리뷰가 달린 PR 은 바뀌지 않으므로 PR 칸은 처음 저장한 값 그대로 둔다
    @Modifying
    @Query(value = """
            INSERT INTO pr_review (repository_id, github_pr_number, collection_run_id, github_review_id, state,
                                   author_login, body_excerpt, submitted_at)
            VALUES (:#{#review.repositoryId}, :#{#review.githubPrNumber}, :#{#review.collectionRunId},
                    :#{#review.githubReviewId}, :#{#review.state.name()}, :#{#review.authorLogin},
                    :#{#review.bodyExcerpt}, :#{#review.submittedAt})
            ON CONFLICT (github_review_id) DO UPDATE
                SET collection_run_id = EXCLUDED.collection_run_id,
                    state             = EXCLUDED.state,
                    author_login      = EXCLUDED.author_login,
                    body_excerpt      = EXCLUDED.body_excerpt,
                    submitted_at      = EXCLUDED.submitted_at
            """, nativeQuery = true)
    void upsert(@Param("review") PrReview review);
}
