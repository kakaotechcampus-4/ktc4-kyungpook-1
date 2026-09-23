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

        JobResponse found = service.load(job.getPublicId(), myUserId);

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
    @DisplayName("진행 중 Job 은 pollAfterMs 2000 을, 끝난 Job 은 0 을 돌려준다")
    void pollAfterMsDependsOnState() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);

        assertThat(service.load(job.getPublicId(), myUserId).pollAfterMs()).isEqualTo(2000);

        job.start();
        job.succeed(false);
        em.flush();

        assertThat(service.load(job.getPublicId(), myUserId).pollAfterMs()).isZero();
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

    private AnalysisJob persistJob(Long userId, Long userRepositoryId, String idempotencyKey) {

        AnalysisJob job = AnalysisJob.enqueue(userId, userRepositoryId, idempotencyKey);
        em.persist(job);
        em.flush();

        return job;
    }
}
