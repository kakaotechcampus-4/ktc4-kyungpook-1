package com.gitory.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gitory.backend.support.TestFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

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

    private void migrateTo(String version) {

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(version)
                .load()
                .migrate();
    }
}
