package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CandidateGroupingPersistenceTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);
    private static final Instant LAST_WEEK = Instant.parse("2026-10-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private Long userRepositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        Long userId = fixtures.insertUser(1L, "grow22");
        userRepositoryId = fixtures.insertUserRepository(userId, fixtures.insertRepository(1L, "grow22", "gitory"));

    }

    @Test
    @DisplayName("경험 그룹화 기록의 칸마다 맞는 값이 저장되고 다시 읽힌다")
    void savesEveryField() {

        CandidateGrouping grouping = CandidateGrouping.of(userRepositoryId, FINGERPRINT, 3);
        em.persist(grouping);
        em.flush();
        em.clear();

        CandidateGrouping found = em.find(CandidateGrouping.class, userRepositoryId);

        assertThat(found.getInputFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(found.getGroupingVersion()).isEqualTo(3);
        assertThat(found.getGroupedAt()).isCloseTo(grouping.getGroupedAt(), within(1, ChronoUnit.MICROS));

    }

    @Test
    @DisplayName("DB 에서 읽은 기록도 지문과 경험 그룹화 버전이 같으면 같은 입력이다")
    void storedGroupingHasSameInput() {

        insertGrouping(FINGERPRINT, 1, LAST_WEEK);

        CandidateGrouping found = em.find(CandidateGrouping.class, userRepositoryId);

        assertThat(found.hasSameInput(FINGERPRINT, 1)).isTrue();

    }

    @Test
    @DisplayName("저장된 기록을 다시 기록하면 지문·경험 그룹화 버전이 바뀌고 경험 그룹화 시각이 앞으로 간다")
    void recordUpdatesStoredRow() {

        insertGrouping(FINGERPRINT, 1, LAST_WEEK);
        CandidateGrouping grouping = em.find(CandidateGrouping.class, userRepositoryId);

        grouping.record(OTHER_FINGERPRINT, 2);
        em.flush();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT input_fingerprint, grouping_version, grouped_at FROM candidate_grouping WHERE user_repository_id = ?",
                userRepositoryId);
        assertThat(row.get("input_fingerprint")).isEqualTo(OTHER_FINGERPRINT);
        assertThat(row.get("grouping_version")).isEqualTo(2);
        assertThat(((Timestamp) row.get("grouped_at")).toInstant()).isAfter(LAST_WEEK);

    }

    @Test
    @DisplayName("저장소 연결마다 경험 그룹화 기록은 하나다")
    void keepsOneRowPerRepositoryLink() {

        insertGrouping(FINGERPRINT, 1, LAST_WEEK);

        assertThatThrownBy(() -> insertGrouping(OTHER_FINGERPRINT, 2, LAST_WEEK))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    @DisplayName("저장소 연결이 지워지면 그 연결의 경험 그룹화 기록도 같이 지워진다")
    void deletesWithRepositoryLink() {

        insertGrouping(FINGERPRINT, 1, LAST_WEEK);

        jdbc.update("DELETE FROM user_repository WHERE id = ?", userRepositoryId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_grouping", Integer.class)).isZero();

    }

    @ParameterizedTest
    @ValueSource(strings = {"input_fingerprint", "grouping_version", "grouped_at"})
    @DisplayName("지문·경험 그룹화 버전·경험 그룹화 시각 중 하나라도 비면 저장되지 않는다")
    void rejectsMissingValue(String column) {

        insertGrouping(FINGERPRINT, 1, LAST_WEEK);

        assertThatThrownBy(() -> jdbc.update("UPDATE candidate_grouping SET " + column + " = NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    private void insertGrouping(String fingerprint, int version, Instant groupedAt) {

        jdbc.update(
                "INSERT INTO candidate_grouping (user_repository_id, input_fingerprint, grouping_version, grouped_at) VALUES (?, ?, ?, ?)",
                userRepositoryId, fingerprint, version, Timestamp.from(groupedAt));

    }
}
