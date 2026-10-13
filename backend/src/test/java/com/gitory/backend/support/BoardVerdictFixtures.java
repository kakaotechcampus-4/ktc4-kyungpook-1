package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/** 후보 보드의 부분 결과 판정과 이유 줄이 읽는 수집 기록·저장소 개수·저장된 커밋 행을 SQL 로 만든다 */
public final class BoardVerdictFixtures {

    private final JdbcTemplate jdbc;

    public BoardVerdictFixtures(JdbcTemplate jdbc) {

        this.jdbc = jdbc;

    }

    /** partialReason 이 null 이면 끝까지 읽은 수집이다 */
    public Long insertRun(Long userRepositoryId, String partialReason, int commitsCollected, int prsCollected,
                          Instant collectedAt) {

        return jdbc.queryForObject("""
                INSERT INTO collection_run (user_repository_id, commits_collected, prs_collected, partial,
                                            partial_reason, collected_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, userRepositoryId, commitsCollected, prsCollected, partialReason != null,
                partialReason, Timestamp.from(collectedAt));

    }

    public void setCounts(Long userRepositoryId, Instant countedAt, int commitCount, int ownCommitCount, int prCount,
                          int ownPrCount, int reviewedPrCount) {

        jdbc.update("""
                UPDATE user_repository SET counted_at = ?, commit_count = ?, own_commit_count = ?, pr_count = ?,
                                           own_pr_count = ?, reviewed_pr_count = ?
                WHERE id = ?
                """, countedAt == null ? null : Timestamp.from(countedAt), commitCount, ownCommitCount, prCount,
                ownPrCount, reviewedPrCount, userRepositoryId);

    }

    /** message 와 authorLogin 은 null 일 수 있고, exclusionReason 이 null 이면 제외하지 않은 커밋이다 */
    public Long insertCommit(Long repositoryId, String sha, String authorLogin, String message,
                             String exclusionReason) {

        return jdbc.queryForObject("""
                INSERT INTO git_commit (repository_id, sha, author_login, message, is_excluded, exclusion_reason)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, repositoryId, sha, authorLogin, message, exclusionReason != null, exclusionReason);

    }
}
