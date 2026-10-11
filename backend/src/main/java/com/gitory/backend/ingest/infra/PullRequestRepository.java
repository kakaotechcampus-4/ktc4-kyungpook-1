package com.gitory.backend.ingest.infra;

import com.gitory.backend.ingest.domain.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    // 다른 Job 이 같은 PR 을 먼저 저장했어도 유니크 위반 없이 이번 수집 값으로 덮어쓴다
    @Modifying
    @Query(value = """
            INSERT INTO pull_request (repository_id, collection_run_id, github_pr_number, title, body_excerpt, state,
                                      author_login, base_branch, head_branch, opened_at, merged_at, commit_shas,
                                      linked_issue_numbers)
            VALUES (:repositoryId, :collectionRunId, :number, :title, :bodyExcerpt, :state, :authorLogin,
                    :baseBranch, :headBranch, :openedAt, :mergedAt, :commitShas, :linkedIssueNumbers)
            ON CONFLICT (repository_id, github_pr_number) DO UPDATE
                SET collection_run_id    = EXCLUDED.collection_run_id,
                    title                = EXCLUDED.title,
                    body_excerpt         = EXCLUDED.body_excerpt,
                    state                = EXCLUDED.state,
                    author_login         = EXCLUDED.author_login,
                    base_branch          = EXCLUDED.base_branch,
                    head_branch          = EXCLUDED.head_branch,
                    opened_at            = EXCLUDED.opened_at,
                    merged_at            = EXCLUDED.merged_at,
                    commit_shas          = EXCLUDED.commit_shas,
                    linked_issue_numbers = EXCLUDED.linked_issue_numbers
            """, nativeQuery = true)
    void upsert(@Param("repositoryId") Long repositoryId,
                @Param("collectionRunId") Long collectionRunId,
                @Param("number") int number,
                @Param("title") String title,
                @Param("bodyExcerpt") String bodyExcerpt,
                @Param("state") String state,
                @Param("authorLogin") String authorLogin,
                @Param("baseBranch") String baseBranch,
                @Param("headBranch") String headBranch,
                @Param("openedAt") Instant openedAt,
                @Param("mergedAt") Instant mergedAt,
                @Param("commitShas") String[] commitShas,
                @Param("linkedIssueNumbers") Integer[] linkedIssueNumbers);
}
