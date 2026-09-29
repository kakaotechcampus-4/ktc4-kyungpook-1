package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gitory.backend.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JobQueryService.class)
class JobQueryServiceTest {

    private static final String KEY_1 = "11111111-1111-4111-8111-111111111111";
    private static final String KEY_2 = "22222222-2222-4222-8222-222222222222";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JobQueryService service;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    private TestFixtures fixtures;

    private Long myUserId;
    private Long myRepoId;
    private Long othersUserId;
    private Long othersRepoId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);

        myUserId = fixtures.insertUser(1L, "grow22");
        othersUserId = fixtures.insertUser(2L, "taehun0208");

        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");

        myRepoId = fixtures.insertUserRepository(myUserId, repositoryId);
        othersRepoId = fixtures.insertUserRepository(othersUserId, repositoryId);
    }

    @Test
    @DisplayName("내 Job 을 공개 id 로 찾으면 프론트가 쓸 값이 그대로 나온다")
    void loadsMyJob() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);

        JobView found = service.load(job.getPublicId(), myUserId);

        assertThat(found.jobId()).isEqualTo(job.getPublicId().toString());
        assertThat(found.type()).isEqualTo(JobType.ANALYZE);
        assertThat(found.state()).isEqualTo(JobState.QUEUED);
        assertThat(found.partial()).isFalse();
        assertThat(found.steps())
                .extracting(JobStep::key)
                .containsExactly(JobStepKey.COMMITS, JobStepKey.PR_REVIEW, JobStepKey.COMPRESS, JobStepKey.REASON);
        assertThat(found.errorCode()).isNull();
        assertThat(found.retryable()).isNull();
        assertThat(found.retryAfterSec()).isNull();
        assertThat(found.result()).isNull();
        assertThat(found.startedAt()).isNotNull();
        assertThat(found.updatedAt()).isNotNull();
        assertThat(found.finishedAt()).isNull();
    }

    @Test
    @DisplayName("남의 Job 은 공개 id 가 맞아도 찾을 수 없다")
    void doesNotLoadOthersJob() {

        AnalysisJob job = persistJob(othersUserId, othersRepoId, KEY_1);

        assertThatThrownBy(() -> service.load(job.getPublicId(), myUserId))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    @DisplayName("없는 공개 id 로 찾으면 찾을 수 없다")
    void doesNotLoadUnknownJob() {

        assertThatThrownBy(() -> service.load(UUID.randomUUID(), myUserId))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    @DisplayName("진행 중 Job 은 terminal 이 false, 끝난 Job 은 true 다")
    void terminalFollowsState() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);

        assertThat(service.load(job.getPublicId(), myUserId).terminal()).isFalse();

        job.start();
        job.succeed(false);
        em.flush();

        assertThat(service.load(job.getPublicId(), myUserId).terminal()).isTrue();
    }

    @Test
    @DisplayName("GitHub 문제로 실패한 Job 만 다시 시도할 수 있다")
    void retryableComesFromErrorCode() {

        AnalysisJob rateLimited = persistJob(myUserId, myRepoId, KEY_1);
        rateLimited.start();
        rateLimited.fail(JobErrorCode.GITHUB_RATE_LIMITED);
        em.flush();

        assertThat(service.load(rateLimited.getPublicId(), myUserId).retryable()).isTrue();

        AnalysisJob internalError = persistJob(myUserId, myRepoId, KEY_2);
        internalError.start();
        internalError.fail(JobErrorCode.INTERNAL_ERROR);
        em.flush();

        assertThat(service.load(internalError.getPublicId(), myUserId).retryable()).isFalse();
    }

    @Test
    @DisplayName("진행 중 목록에는 내 Job 만 나온다")
    void listsOnlyMyActiveJobs() {

        AnalysisJob mine = persistJob(myUserId, myRepoId, KEY_1);
        persistJob(othersUserId, othersRepoId, KEY_2);

        assertThat(service.loadActive(myUserId))
                .extracting(ActiveJobRow::jobId)
                .containsExactly(mine.getPublicId());
    }

    @Test
    @DisplayName("끝난 Job 은 진행 중 목록에 나오지 않는다")
    void hidesFinishedJobs() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);
        job.start();
        job.succeed(false);
        em.flush();

        assertThat(service.loadActive(myUserId)).isEmpty();
    }

    @Test
    @DisplayName("진행 중 Job 이 하나도 없으면 빈 목록이 나온다")
    void returnsEmptyListWhenNothingIsRunning() {

        assertThat(service.loadActive(myUserId)).isEmpty();
    }

    @Test
    @DisplayName("각 행의 필드마다 맞는 값이 담긴다")
    void fillsEveryFieldOfARow() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);

        UUID userRepositoryPublicId = jdbc.queryForObject(
                "SELECT public_id FROM user_repository WHERE id = ?", UUID.class, myRepoId);

        ActiveJobRow row = service.loadActive(myUserId).get(0);

        assertThat(row.jobId()).isEqualTo(job.getPublicId());
        assertThat(row.state()).isEqualTo("QUEUED");
        assertThat(row.type()).isEqualTo("ANALYZE");
        assertThat(row.userRepositoryId()).isEqualTo(userRepositoryPublicId);
        assertThat(row.repoName()).isEqualTo("grow22/gitory");
        assertThat(row.startedAt()).isNotNull();
    }

    @Test
    @DisplayName("최근에 시작한 Job 이 먼저 나온다")
    void newestJobComesFirst() {

        AnalysisJob older = persistJob(myUserId, myRepoId, KEY_1);

        Long secondRepositoryId = fixtures.insertRepository(2L, "grow22", "geojero");
        Long secondRepoId = fixtures.insertUserRepository(myUserId, secondRepositoryId);
        AnalysisJob newer = persistJob(myUserId, secondRepoId, KEY_2);

        setStartedAt(older, Instant.parse("2026-09-29T10:00:00Z"));
        setStartedAt(newer, Instant.parse("2026-09-29T11:00:00Z"));

        assertThat(service.loadActive(myUserId))
                .extracting(ActiveJobRow::jobId)
                .containsExactly(newer.getPublicId(), older.getPublicId());
    }

    private void setStartedAt(AnalysisJob job, Instant startedAt) {

        jdbc.update("UPDATE analysis_job SET started_at = ? WHERE public_id = ?",
                Timestamp.from(startedAt), job.getPublicId());
    }

    private AnalysisJob persistJob(Long userId, Long userRepositoryId, String idempotencyKey) {

        AnalysisJob job = AnalysisJob.enqueue(userId, userRepositoryId, idempotencyKey);
        em.persist(job);
        em.flush();

        return job;
    }
}
