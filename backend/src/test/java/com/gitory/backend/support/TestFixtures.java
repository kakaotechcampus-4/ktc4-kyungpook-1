package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 테스트가 쓸 부모 행을 만든다. analysis_job 은 users · user_repository 를 참조해서 그 행이 먼저 있어야 한다.
 */
public final class TestFixtures {

    private final JdbcTemplate jdbc;

    public TestFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.execute("TRUNCATE users, repository CASCADE");
    }

    public Long insertUser(Long githubUserId, String login) {
        return jdbc.queryForObject(
                "INSERT INTO users (github_user_id, github_login) VALUES (?, ?) RETURNING id",
                Long.class, githubUserId, login);
    }

    public Long insertRepository(Long githubRepoId, String ownerLogin, String name) {
        return jdbc.queryForObject(
                "INSERT INTO repository (github_repo_id, owner_login, name, visibility) VALUES (?, ?, ?, 'PUBLIC') RETURNING id",
                Long.class, githubRepoId, ownerLogin, name);
    }

    public Long insertUserRepository(Long userId, Long repositoryId) {
        return jdbc.queryForObject(
                "INSERT INTO user_repository (user_id, repository_id) VALUES (?, ?) RETURNING id",
                Long.class, userId, repositoryId);
    }

    public Long insertUserRepository(UUID publicId, Long userId, Long repositoryId) {
        return jdbc.queryForObject(
                "INSERT INTO user_repository (public_id, user_id, repository_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, publicId, userId, repositoryId);
    }

    public Long insertCommit(Long repositoryId, String sha) {

        return jdbc.queryForObject(
                "INSERT INTO git_commit (repository_id, sha) VALUES (?, ?) RETURNING id",
                Long.class, repositoryId, sha);

    }

    /** 직접 작성한 작성 중 카드와 빈 1번 직접 수정 버전을 만든다 */
    public Long insertCard(UUID publicId, Long userId) {

        Long cardId = jdbc.queryForObject(
                "INSERT INTO card (public_id, user_id, card_type, origin, title) VALUES (?, ?, 'QUALITATIVE', 'MANUAL', '카드') RETURNING id",
                Long.class, publicId, userId);
        insertCardVersion(cardId, 1, "USER_EDIT", false);

        return cardId;

    }

    /** 버전을 더하고 카드의 현재 버전으로 삼는다 */
    public void insertCardVersion(Long cardId, int versionNo, String source, boolean confirmed) {

        jdbc.update("INSERT INTO card_version (card_id, version_no, source, is_confirmed) VALUES (?, ?, ?, ?)",
                cardId, versionNo, source, confirmed);
        jdbc.update("UPDATE card SET current_version = ? WHERE id = ?", versionNo, cardId);

    }

    /** 확정 상태와 시각만 바꾸므로, 현재 버전을 확정본으로 둘 때는 insertCardVersion 으로 그 버전을 만든다 */
    public void confirmCard(Long cardId) {

        jdbc.update("UPDATE card SET status = 'CONFIRMED', confirmed_at = now() WHERE id = ?", cardId);

    }

    public Long insertStatement(Long cardId, int versionNo, String slot, String body, String evidenceType,
                                String confidence) {

        return insertStatement(cardId, versionNo, slot, body, evidenceType, confidence, null);

    }

    public Long insertStatement(Long cardId, int versionNo, String slot, String body, String evidenceType,
                                String confidence, Long sourceTurnId) {

        return jdbc.queryForObject(
                "INSERT INTO card_statement (card_id, version_no, star_slot, seq, body, evidence_type, confidence, source_turn_id) VALUES (?, ?, ?, 1, ?, ?, ?, ?) RETURNING id",
                Long.class, cardId, versionNo, slot, body, evidenceType, confidence, sourceTurnId);

    }

    public Long insertInterviewTurn(Long cardId, int seq, String slot) {

        return jdbc.queryForObject(
                "INSERT INTO interview_turn (card_id, seq, star_slot, question_type, trigger_source, question_text) VALUES (?, ?, ?, 'EVIDENCE_GAP', 'USER', '질문') RETURNING id",
                Long.class, cardId, seq, slot);

    }

    public void insertStatementEvidence(Long statementId, Long commitId) {

        jdbc.update("INSERT INTO statement_evidence (statement_id, commit_id) VALUES (?, ?)", statementId, commitId);

    }
}
