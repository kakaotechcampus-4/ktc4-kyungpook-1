package com.gitory.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 후보로 카드를 만드는 테스트가 쓰는 후보·초안 Job 행을 SQL 로 만든다
 * ref 와 제목을 정할 수 있고, 후보 공개 id 와 카드 행을 읽는 도우미도 함께 둔다
 */
public final class CandidateCardFixtures {

    private final JdbcTemplate jdbc;

    public CandidateCardFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** PR 후보는 번호를, 커밋 묶음 후보는 null 을 받는다 */
    public Long insertCandidate(Long userRepositoryId, String sourceType, Integer prNumber, String ref, String title,
                                String status) {

        return jdbc.queryForObject(
                "INSERT INTO candidate (user_repository_id, source_type, github_pr_number, source_ref, title, reason_text, status) VALUES (?, ?, ?, ?, ?, '이유', ?) RETURNING id",
                Long.class, userRepositoryId, sourceType, prNumber, ref, title, status);

    }

    public Long insertNewCandidate(Long userRepositoryId, String title) {

        return insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "5.12", title, "NEW");

    }

    /** 직접 추가하거나 회상으로 만든 새 후보로, 이유 칸에 사용자가 쓴 글이 들어간다 */
    public Long insertManualCandidate(Long userRepositoryId, String title, String reasonText) {

        return insertManualCandidate(userRepositoryId, "MANUAL", title, reasonText);

    }

    /** 회상 후보는 ref 가 RECALL 이라 ref 를 정할 수 있다 */
    public Long insertManualCandidate(Long userRepositoryId, String ref, String title, String reasonText) {

        return jdbc.queryForObject(
                "INSERT INTO candidate (user_repository_id, source_type, source_ref, title, reason_text, status, is_user_modified) VALUES (?, 'MANUAL', ?, ?, ?, 'NEW', true) RETURNING id",
                Long.class, userRepositoryId, ref, title, reasonText);

    }

    /** 복원이나 커밋 빼기로 손댄 AI 후보로, 이유 칸은 AI 가 쓴 글이다 */
    public Long insertUserModifiedCandidate(Long userRepositoryId, String title) {

        return jdbc.queryForObject(
                "INSERT INTO candidate (user_repository_id, source_type, source_ref, title, reason_text, status, is_user_modified) VALUES (?, 'COMMIT_CLUSTER', '5.12', ?, '같은 날 이어진 작업', 'NEW', true) RETURNING id",
                Long.class, userRepositoryId, title);

    }

    public UUID publicIdOf(Long candidateId) {

        return jdbc.queryForObject("SELECT public_id FROM candidate WHERE id = ?", UUID.class, candidateId);

    }

    public void insertCandidateCommit(Long candidateId, Long commitId, boolean included) {

        jdbc.update("INSERT INTO candidate_commit (candidate_id, commit_id, origin, is_included) VALUES (?, ?, 'AI', ?)",
                candidateId, commitId, included);

    }

    public String statusOf(Long candidateId) {

        return jdbc.queryForObject("SELECT status FROM candidate WHERE id = ?", String.class, candidateId);

    }

    /** 키가 겹치지 않는 카드 초안 Job 을 주어진 상태로 만들고 그 공개 id 를 돌려준다 */
    public UUID insertDraftJob(Long userId, Long userRepositoryId, String state) {

        return jdbc.queryForObject(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, 'DRAFT', ?) RETURNING public_id",
                UUID.class, userId, userRepositoryId, UUID.randomUUID().toString(), state);

    }

    /** 후보로 만든 카드 한 장과 빈 1번 버전을 만들고 카드 id 를 돌려준다 */
    public Long insertCandidateCard(Long userId, Long userRepositoryId, Long candidateId, UUID generationJobId,
                                    boolean generationPartial) {

        Long cardId = jdbc.queryForObject(
                "INSERT INTO card (user_id, user_repository_id, candidate_id, card_type, origin, title, generation_job_id, generation_partial) VALUES (?, ?, ?, 'TECH', 'AI', '후보 카드', ?, ?) RETURNING id",
                Long.class, userId, userRepositoryId, candidateId, generationJobId, generationPartial);
        jdbc.update("INSERT INTO card_version (card_id, version_no, source) VALUES (?, 1, 'USER_EDIT')", cardId);

        return cardId;

    }

    public UUID cardPublicIdOf(Long cardId) {

        return jdbc.queryForObject("SELECT public_id FROM card WHERE id = ?", UUID.class, cardId);

    }
}
