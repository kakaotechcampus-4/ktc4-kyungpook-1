package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AnalysisJobPersistenceTest {

    private static final String KEY = "11111111-1111-4111-8111-111111111111";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private Long userId;
    private Long userRepositoryId;

    // analysis_job 은 users · user_repository 를 FK 로 참조하기 때문에 부모 행을 먼저 생성 후 테스트 진행
    @BeforeEach
    void setUp() {

        userId = jdbc.queryForObject(
                "INSERT INTO users (github_user_id, github_login) VALUES (1, 'grow22') RETURNING id",
                Long.class);

        Long repositoryId = jdbc.queryForObject(
                "INSERT INTO repository (github_repo_id, owner_login, name, visibility) VALUES (1, 'grow22', 'gitory', 'PUBLIC') RETURNING id",
                Long.class);

        userRepositoryId = jdbc.queryForObject(
                "INSERT INTO user_repository (user_id, repository_id) VALUES (?, ?) RETURNING id",
                Long.class, userId, repositoryId);
    }

    @Test
    @DisplayName("Job을 DB에 저장했다가 다시 꺼내면 같은 값이 나온다.")
    void savedJobCanBeLoaded() {

        AnalysisJob job = AnalysisJob.enqueue(userId, userRepositoryId, KEY);
        em.persist(job);
        em.flush();
        em.clear();

        AnalysisJob loaded = em.find(AnalysisJob.class, job.getId());

        assertThat(loaded.getState()).isEqualTo(JobState.QUEUED);
        assertThat(loaded.isPartial()).isFalse();
    }

    @Test
    @DisplayName("상태와 errorCode 는 영문 문자열로, 시각 3개는 모두 채워져 저장된다")
    void stateAndTimesAreStored() {

        AnalysisJob job = AnalysisJob.enqueue(userId, userRepositoryId, KEY);
        em.persist(job);
        job.start();
        job.fail(JobErrorCode.RATE_LIMITED);
        em.flush();
        em.clear();

        AnalysisJob loaded = em.find(AnalysisJob.class, job.getId());

        assertThat(loaded.getState()).isEqualTo(JobState.FAILED);
        assertThat(loaded.getErrorCode()).isEqualTo(JobErrorCode.RATE_LIMITED);
        assertThat(loaded.getStartedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getFinishedAt()).isNotNull();
    }
}
