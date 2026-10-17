package com.gitory.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.support.CandidateFixtures;
import com.gitory.backend.support.TestFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이전 버전 데이터가 들어 있는 DB 에 다음 마이그레이션을 적용해도 실패하지 않고 값이 새 이름으로 바뀌는지 본다
 * Spring 을 띄우면 Flyway 가 최신 버전까지 한 번에 적용해 중간에 멈출 수 없어서 Flyway 를 직접 실행한다
 */
@Testcontainers
class MigrationUpgradeTest {

    @Container
    PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private TestFixtures fixtures;

    @BeforeEach
    void setUp() {

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        fixtures = new TestFixtures(jdbc);
    }

    @Test
    @DisplayName("V4 는 Job 실패 코드 RATE_LIMITED 만 GITHUB_RATE_LIMITED 로 바꾸고 나머지는 그대로 둔다")
    void v4RenamesJobErrorCode() {

        migrateTo("3");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        for (String errorCode : List.of("GITHUB_UNAVAILABLE", "RATE_LIMITED", "DRAFT_TIMEOUT", "EVIDENCE_MISSING", "INTERNAL_ERROR")) {
            jdbc.update(
                    "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, state, error_code) VALUES (?, ?, ?, 'FAILED', ?)",
                    userId, userRepositoryId, errorCode, errorCode);
        }

        migrateTo("4");

        assertThat(jdbc.queryForList("SELECT error_code FROM analysis_job", String.class))
                .containsExactlyInAnyOrder("GITHUB_UNAVAILABLE", "GITHUB_RATE_LIMITED", "DRAFT_TIMEOUT", "EVIDENCE_MISSING", "INTERNAL_ERROR");
    }

    @Test
    @DisplayName("V4 는 수집 이력의 부분 수집 사유 RATE_LIMITED 만 GITHUB_RATE_LIMITED 로 바꾸고 나머지는 그대로 둔다")
    void v4RenamesPartialReason() {

        migrateTo("3");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        for (String partialReason : List.of("RATE_LIMITED", "CAP_EXCEEDED", "TIMEOUT")) {
            jdbc.update(
                    "INSERT INTO collection_run (user_repository_id, partial, partial_reason) VALUES (?, TRUE, ?)",
                    userRepositoryId, partialReason);
        }

        migrateTo("4");

        assertThat(jdbc.queryForList("SELECT partial_reason FROM collection_run", String.class))
                .containsExactlyInAnyOrder("GITHUB_RATE_LIMITED", "CAP_EXCEEDED", "TIMEOUT");
    }

    @Test
    @DisplayName("V5 는 커밋 제외 사유 MERGE 를 MERGE_COMMIT 으로 바꾸고 새 목록에도 있는 값은 그대로 둔다")
    void v5RenamesExclusionReason() {

        migrateTo("4");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        jdbc.update(
                "INSERT INTO git_commit (repository_id, sha, is_excluded, exclusion_reason) VALUES (?, ?, TRUE, 'MERGE')",
                repositoryId, "a".repeat(40));
        jdbc.update(
                "INSERT INTO git_commit (repository_id, sha, is_excluded, exclusion_reason) VALUES (?, ?, TRUE, 'BOT')",
                repositoryId, "b".repeat(40));

        migrateTo("5");

        assertThat(jdbc.queryForList("SELECT exclusion_reason FROM git_commit", String.class))
                .containsExactlyInAnyOrder("MERGE_COMMIT", "BOT");
    }

    @Test
    @DisplayName("V12 는 저장된 NOT_OWN 커밋만 제외하지 않은 커밋으로 되돌리고 다른 제외 사유는 그대로 두며 옛 커밋의 이슈 번호는 NULL 로 남긴다")
    void v12ClearsNotOwnAndLeavesIssueNumbersUnfilled() {

        migrateTo("11");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        List<String> reasons = List.of("BOT", "MERGE_COMMIT", "LOCKFILE_ONLY", "NOT_OWN");
        for (int i = 0; i < reasons.size(); i++) {
            jdbc.update(
                    "INSERT INTO git_commit (repository_id, sha, is_excluded, exclusion_reason) VALUES (?, ?, TRUE, ?)",
                    repositoryId, String.valueOf(i).repeat(40), reasons.get(i));
        }
        jdbc.update("INSERT INTO git_commit (repository_id, sha) VALUES (?, ?)", repositoryId, "9".repeat(40));

        migrateTo("12");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT exclusion_reason, is_excluded, issue_numbers IS NULL AS unfilled FROM git_commit ORDER BY sha");
        assertThat(rows).extracting(row -> row.get("exclusion_reason"))
                .containsExactly("BOT", "MERGE_COMMIT", "LOCKFILE_ONLY", null, null);
        assertThat(rows).extracting(row -> row.get("is_excluded"))
                .containsExactly(true, true, true, false, false);
        assertThat(rows).extracting(row -> row.get("unfilled")).containsOnly(true);

    }

    @Test
    @DisplayName("V14 는 후보 상태 PROPOSED·CONFIRMED 를 NEW 로 바꾸고 USED·EXCLUDED 와 종류 PR·COMMIT_CLUSTER 는 그대로 둔다")
    void v14MovesCandidateStatusToScreenValues() {

        migrateTo("12");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        CandidateFixtures candidates = new CandidateFixtures(jdbc);
        candidates.insertCandidate(userRepositoryId, "PR", 1, "PROPOSED");
        candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "CONFIRMED");
        candidates.insertCandidate(userRepositoryId, "PR", 2, "USED");
        candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED");

        migrateTo("14");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT source_type, status FROM candidate ORDER BY id");
        assertThat(rows).extracting(row -> row.get("status"))
                .containsExactly("NEW", "NEW", "USED", "EXCLUDED");
        assertThat(rows).extracting(row -> row.get("source_type"))
                .containsExactly("PR", "COMMIT_CLUSTER", "PR", "COMMIT_CLUSTER");

    }

    @Test
    @DisplayName("V14 는 CONFIRMED 였던 후보만 손댄 표시를 켜고 나머지 후보의 표시는 그대로 둔다")
    void v14MarksConfirmedCandidatesAsUserModified() {

        migrateTo("12");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        CandidateFixtures candidates = new CandidateFixtures(jdbc);
        for (String status : List.of("PROPOSED", "CONFIRMED", "USED", "EXCLUDED")) {
            Long candidateId = candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, status);
            if (status.equals("USED")) {
                jdbc.update("UPDATE candidate SET is_user_modified = TRUE WHERE id = ?", candidateId);
            }
        }
        Long modifiedId = candidates.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "PROPOSED");
        jdbc.update("UPDATE candidate SET is_user_modified = TRUE WHERE id = ?", modifiedId);

        migrateTo("14");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, is_user_modified FROM candidate ORDER BY id");
        assertThat(rows).extracting(row -> row.get("status"))
                .containsExactly("NEW", "NEW", "USED", "EXCLUDED", "NEW");
        assertThat(rows).extracting(row -> row.get("is_user_modified"))
                .containsExactly(false, true, true, false, true);

    }

    @Test
    @DisplayName("V14 는 옛 후보의 PR·이슈 번호 목록을 빈 목록으로, 파일 수·리뷰 코멘트 수를 0 으로 두고 ref·기간·코드 비율은 비우며 옛 후보 커밋의 표시 경로는 빈 글자로 둔다")
    void v14FillsNewColumnsOfOldRows() {

        migrateTo("12");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        Long userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        CandidateFixtures candidates = new CandidateFixtures(jdbc);
        Long candidateId = candidates.insertCandidate(userRepositoryId, "PR", 42, "PROPOSED");
        Long commitId = jdbc.queryForObject("INSERT INTO git_commit (repository_id, sha) VALUES (?, ?) RETURNING id",
                Long.class, repositoryId, "a".repeat(40));
        candidates.insertCandidateCommit(candidateId, commitId);

        migrateTo("14");

        Map<String, Object> candidate = jdbc.queryForMap(
                "SELECT cardinality(pull_request_numbers) AS prs, cardinality(issue_numbers) AS issues, file_count,"
                        + " review_comment_count, source_ref, span_days, code_ratio FROM candidate");
        assertThat(candidate.get("prs")).isEqualTo(0);
        assertThat(candidate.get("issues")).isEqualTo(0);
        assertThat(candidate.get("file_count")).isEqualTo(0);
        assertThat(candidate.get("review_comment_count")).isEqualTo(0);
        assertThat(candidate.get("source_ref")).isNull();
        assertThat(candidate.get("span_days")).isNull();
        assertThat(candidate.get("code_ratio")).isNull();
        assertThat(jdbc.queryForObject("SELECT display_path FROM candidate_commit", String.class)).isEmpty();

    }

    @Test
    @DisplayName("V15 는 옛 Job 행의 남은 초를 NULL 로 두고, 0 이하 값은 CHECK 로 거절하며 양수는 받는다")
    void v15AddsRetryAfterSecColumn() {

        migrateTo("14");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        jdbc.update(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, state, error_code) VALUES (?, ?, ?, 'SUCCEEDED', 'GITHUB_RATE_LIMITED')",
                userId, userRepositoryId, "old-job");

        migrateTo("15");

        assertThat(jdbc.queryForObject("SELECT retry_after_sec FROM analysis_job", Integer.class)).isNull();
        assertThat(updateRetryAfterSec(60)).isEqualTo(1);
        assertThatThrownBy(() -> updateRetryAfterSec(0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> updateRetryAfterSec(-5)).isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("V16 은 옛 카드의 초안 Job 을 비우고 부분 생성 표시를 끄며, 후보 하나로 살아 있는 카드가 둘 생기지 않게 막되 지운 카드와 후보 없는 카드는 막지 않는다")
    void v16AddsGenerationColumnsAndCandidateUnique() {

        migrateTo("14");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));
        Long candidateId = new CandidateFixtures(jdbc).insertCandidate(userRepositoryId, "PR", 1, "NEW");
        Long oldCard = insertCard(userId, userRepositoryId, candidateId);

        migrateTo("16");

        Map<String, Object> migrated = jdbc.queryForMap("SELECT generation_job_id, generation_partial FROM card");
        assertThat(migrated.get("generation_job_id")).isNull();
        assertThat(migrated.get("generation_partial")).isEqualTo(false);
        assertThatThrownBy(() -> insertCard(userId, userRepositoryId, candidateId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE card SET deleted_at = now() WHERE id = ?", oldCard);
        insertCard(userId, userRepositoryId, candidateId);
        insertCard(userId, userRepositoryId, null);
        insertCard(userId, userRepositoryId, null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isEqualTo(4);

    }

    @Test
    @DisplayName("V16 은 카드를 초안 Job 으로 찾는 인덱스를 초안 Job 이 있는 카드에만 만든다")
    void v16AddsPartialIndexOnGenerationJob() {

        migrateTo("16");

        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'card' AND indexname = 'idx_card_generation_job'",
                String.class);
        assertThat(definition)
                .contains("(generation_job_id)")
                .contains("WHERE (generation_job_id IS NOT NULL)");

    }

    @Test
    @DisplayName("V18 칸 메모는 비운 이유와 확인 필요 이유 중 하나만 받고, 목록 밖 이유 코드와 같은 칸의 두 번째 메모는 막는다")
    void v18SlotNoteKeepsOneKnownReason() {

        migrateTo("18");
        Long userId = fixtures.insertUser(1L, "grow22");
        Long cardId = fixtures.insertCard(UUID.randomUUID(), userId);

        fixtures.insertSlotNote(cardId, 1, "S", "NO_EVIDENCE", null);
        fixtures.insertSlotNote(cardId, 1, "A", null, "근거에 없는 수치");

        assertThatThrownBy(() -> fixtures.insertSlotNote(cardId, 1, "R", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixtures.insertSlotNote(cardId, 1, "R", "TIMEOUT", "둘 다"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixtures.insertSlotNote(cardId, 1, "R", "LATER", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixtures.insertSlotNote(cardId, 1, "S", "TIMEOUT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fixtures.insertSlotNote(cardId, 2, "R", "TIMEOUT", null))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    private int updateRetryAfterSec(int seconds) {

        return jdbc.update("UPDATE analysis_job SET retry_after_sec = ?", seconds);

    }

    private Long insertCard(Long userId, Long userRepositoryId, Long candidateId) {

        return jdbc.queryForObject(
                "INSERT INTO card (user_id, user_repository_id, candidate_id, card_type, origin, title) VALUES (?, ?, ?, 'TECH', 'AI', '카드') RETURNING id",
                Long.class, userId, userRepositoryId, candidateId);

    }

    private void migrateTo(String version) {

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(version)
                .load()
                .migrate();
    }
}
