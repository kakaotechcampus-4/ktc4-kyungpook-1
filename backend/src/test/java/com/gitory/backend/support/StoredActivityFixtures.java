package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/** 수집해 저장한 커밋·바뀐 파일·PR·줄 댓글 행을 SQL 로 만든다 */
public final class StoredActivityFixtures {

    private final JdbcTemplate jdbc;

    public StoredActivityFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertCommit(Long repositoryId, String sha, Instant authoredAt, Integer changedFiles) {

        return jdbc.queryForObject(
                "INSERT INTO git_commit (repository_id, sha, author_login, authored_at, changed_files) VALUES (?, ?, 'grow22', ?, ?) RETURNING id",
                Long.class, repositoryId, sha, authoredAt == null ? null : Timestamp.from(authoredAt), changedFiles);

    }

    public void insertFile(Long commitId, String path, int additions, int deletions) {

        jdbc.update(
                "INSERT INTO commit_file (commit_id, path, status, additions, deletions) VALUES (?, ?, 'MODIFIED', ?, ?)",
                commitId, path, additions, deletions);

    }

    public void insertPullRequest(Long repositoryId, int number) {

        jdbc.update(
                "INSERT INTO pull_request (repository_id, github_pr_number, title, state, base_branch, head_branch, opened_at) VALUES (?, ?, 'PR', 'CLOSED', 'develop', 'feature', now())",
                repositoryId, number);

    }

    public void insertReviewComment(Long repositoryId, int pullRequestNumber, long githubCommentId) {

        jdbc.update(
                "INSERT INTO pr_review_comment (repository_id, github_pr_number, github_comment_id, body_excerpt, commented_at) VALUES (?, ?, ?, '댓글', now())",
                repositoryId, pullRequestNumber, githubCommentId);

    }
}
