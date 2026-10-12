package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 후보 보드가 읽는 후보·후보 커밋·묶은 기록·분석 시각·카드 행을 SQL 로 만든다 */
public final class CandidateBoardFixtures {

    private final JdbcTemplate jdbc;

    public CandidateBoardFixtures(JdbcTemplate jdbc) {

        this.jdbc = jdbc;

    }

    public Long insertCandidate(Long userRepositoryId, String sourceType, Integer githubPrNumber, String status,
                                String title, String score) {

        return jdbc.queryForObject("""
                INSERT INTO candidate (user_repository_id, source_type, github_pr_number, status, title, reason_text, score)
                VALUES (?, ?, ?, ?, ?, '추천 이유', ?) RETURNING id
                """, Long.class, userRepositoryId, sourceType, githubPrNumber, status, title,
                score == null ? null : new BigDecimal(score));

    }

    /** 묶을 때 저장하는 화면 숫자와 ref 를 채운다 */
    public void setDisplay(Long candidateId, String sourceRef, boolean lowCardWorth, int fileCount,
                           int reviewCommentCount, Integer spanDays, String codeRatio) {

        jdbc.update("""
                UPDATE candidate SET source_ref = ?, low_card_worth = ?, file_count = ?, review_comment_count = ?,
                                     span_days = ?, code_ratio = ?
                WHERE id = ?
                """, sourceRef, lowCardWorth, fileCount, reviewCommentCount, spanDays,
                codeRatio == null ? null : new BigDecimal(codeRatio), candidateId);

    }

    public UUID publicIdOf(Long candidateId) {

        return jdbc.queryForObject("SELECT public_id FROM candidate WHERE id = ?", UUID.class, candidateId);

    }

    public void insertCandidateCommit(Long candidateId, Long commitId, boolean included, String displayPath) {

        jdbc.update("""
                INSERT INTO candidate_commit (candidate_id, commit_id, origin, is_included, display_path)
                VALUES (?, ?, 'AI', ?, ?)
                """, candidateId, commitId, included, displayPath);

    }

    /** 사용자가 빼는 흐름처럼 같은 행을 고치고 통계를 갱신해, 조회가 정렬 없이는 물리적 순서대로 읽게 한다 */
    public void excludeCommit(Long candidateId, Long commitId) {

        jdbc.update("UPDATE candidate_commit SET is_included = false WHERE candidate_id = ? AND commit_id = ?",
                candidateId, commitId);
        jdbc.execute("ANALYZE candidate_commit");

    }

    /** 작성자 로그인과 제외 사유를 정한 커밋을 넣고, 제외 사유가 있으면 제외 표시도 켠다 */
    public Long insertCommitBy(Long repositoryId, String sha, String authorLogin, String exclusionReason) {

        return jdbc.queryForObject("""
                INSERT INTO git_commit (repository_id, sha, author_login, authored_at, is_excluded, exclusion_reason)
                VALUES (?, ?, ?, now(), ?, ?) RETURNING id
                """, Long.class, repositoryId, sha, authorLogin, exclusionReason != null, exclusionReason);

    }

    public void insertFile(Long commitId, String path) {

        jdbc.update("INSERT INTO commit_file (commit_id, path, status, additions, deletions) VALUES (?, ?, 'MODIFIED', 1, 0)",
                commitId, path);

    }

    public void setCommitMessage(Long commitId, String message) {

        jdbc.update("UPDATE git_commit SET message = ? WHERE id = ?", message, commitId);

    }

    public void insertGrouping(Long userRepositoryId, Instant groupedAt) {

        jdbc.update("""
                INSERT INTO candidate_grouping (user_repository_id, input_fingerprint, grouping_version, grouped_at)
                VALUES (?, ?, 1, ?)
                """, userRepositoryId, "0".repeat(64), Timestamp.from(groupedAt));

    }

    public void setLastAnalyzedAt(Long userRepositoryId, Instant analyzedAt) {

        jdbc.update("UPDATE user_repository SET last_analyzed_at = ? WHERE id = ?", Timestamp.from(analyzedAt),
                userRepositoryId);

    }

    /** 후보로 만든 카드 행을 넣고, deleted 면 지운 카드로 표시한다 */
    public void insertCardOf(Long candidateId, Long userId, UUID cardPublicId, boolean deleted) {

        jdbc.update("""
                INSERT INTO card (public_id, user_id, candidate_id, card_type, origin, title, deleted_at)
                VALUES (?, ?, ?, 'QUALITATIVE', 'MANUAL', '카드', ?)
                """, cardPublicId, userId, candidateId, deleted ? Timestamp.from(Instant.now()) : null);

    }
}
