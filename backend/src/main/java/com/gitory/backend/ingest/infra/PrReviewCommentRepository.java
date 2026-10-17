package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.PrReviewComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrReviewCommentRepository extends JpaRepository<PrReviewComment, Long> {

    // 다른 Job 이 같은 댓글을 먼저 저장했어도 유니크 위반 없이 이번 수집 값으로 덮어쓴다
    // 댓글이 달린 PR 과 처음 단 시각은 바뀌지 않으므로 그 칸은 처음 저장한 값 그대로 둔다
    // 코드가 바뀌어 지난 줄이 된 댓글은 GitHub 이 line 을 비워 주고 AI 는 line 만 읽으므로, 빈 line 으로는 저장된 줄 번호를 지우지 않는다
    @Modifying
    @Query(value = """
            INSERT INTO pr_review_comment (repository_id, github_pr_number, collection_run_id, github_comment_id,
                                           github_review_id, author_login, body_excerpt, path, line, commented_at)
            VALUES (:#{#comment.repositoryId}, :#{#comment.githubPrNumber}, :#{#comment.collectionRunId},
                    :#{#comment.githubCommentId}, :#{#comment.githubReviewId}, :#{#comment.authorLogin},
                    :#{#comment.bodyExcerpt}, :#{#comment.path}, :#{#comment.line}, :#{#comment.commentedAt})
            ON CONFLICT (github_comment_id) DO UPDATE
                SET collection_run_id = EXCLUDED.collection_run_id,
                    github_review_id  = EXCLUDED.github_review_id,
                    author_login      = EXCLUDED.author_login,
                    body_excerpt      = EXCLUDED.body_excerpt,
                    path              = EXCLUDED.path,
                    line              = COALESCE(EXCLUDED.line, pr_review_comment.line)
            """, nativeQuery = true)
    void upsert(@Param("comment") PrReviewComment comment);
}
