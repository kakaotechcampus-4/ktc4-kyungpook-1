package com.gitory.backend.dependency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 가 실제로 마이그레이션을 돌렸는지 본다.
 *
 * #11 은 flyway-core 만 넣고 스타터를 빠뜨려 마이그레이션이 한 줄도 실행되지
 * 않은 사고였다. 그때 초록이던 테스트들은 스키마가 없다는 사실을 몰랐던 게 아니라
 * 스키마를 보는 테스트가 없었다. 설정을 적은 것과 설정이 일한 것은 다르다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FlywayMigrationTest {

    private static final String MIGRATION_PATTERN = "classpath*:db/migration/V*__*.sql";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("db/migration 의 마이그레이션이 하나도 빠짐없이 success 로 적용돼 있다")
    void everyMigrationOnTheClasspathIsApplied() throws IOException {
        // 기대값을 상수로 박아 두면 V5 를 추가한 사람이 이 테스트도 같이 고쳐야 한다.
        // 파일 자체를 기대값으로 삼으면 "추가한 마이그레이션이 안 돌았다"가 자동으로 잡힌다.
        List<String> onClasspath = migrationVersionsOnClasspath();
        assertThat(onClasspath).as("db/migration 이 비어 있다 — 테스트가 아무것도 검사하지 않는다").isNotEmpty();

        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL",
                String.class);

        assertThat(applied)
                .as("파일로는 있는데 적용되지 않은 마이그레이션이 있다 — Flyway 가 안 돌았거나 일부만 돌았다")
                .containsExactlyInAnyOrderElementsOf(onClasspath);
    }

    /**
     * 파일명에서 Flyway 버전을 뽑는다 — V4__github_rate_limited.sql 이면 4.
     * Flyway 는 버전의 밑줄을 점으로 바꿔 기록하므로(V2_1 → 2.1) 여기서도 맞춘다.
     */
    private static List<String> migrationVersionsOnClasspath() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources(MIGRATION_PATTERN);
        return Arrays.stream(scripts)
                .map(Resource::getFilename)
                .map(name -> name.substring(1, name.indexOf("__")).replace('_', '.'))
                .toList();
    }
}
