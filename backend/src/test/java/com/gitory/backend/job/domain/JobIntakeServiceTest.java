package com.gitory.backend.job.domain;


import com.gitory.backend.job.infra.AnalysisJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JobIntakeService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobIntakeServiceTest {

    private static final String KEY_1 = "11111111-1111-4111-8111-111111111111";
    private static final String KEY_2 = "22222222-2222-4222-8222-222222222222";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JobIntakeService service;

    @Autowired
    AnalysisJobRepository jobRepository;

    @Autowired
    JdbcTemplate jdbc;

    private Long userId;
    private Long repoA;
    private Long repoB;

    // 트랜잭션 롤백이 없으므로 이전 테스트의 행을 직접 지우고 시작한다
    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE users, repository CASCADE");

        userId = jdbc.queryForObject(
                "INSERT INTO users (github_user_id, github_login) VALUES (1, 'grow22') RETURNING id",
                Long.class);

        repoA = connectRepository(1L, "repo-a");
        repoB = connectRepository(2L, "repo-b");
    }

    // 레포를 만들고 userId 사용자에게 연결한 뒤 user_repository.id 를 돌려준다
    private Long connectRepository(Long githubRepoId, String name) {

        Long repositoryId = jdbc.queryForObject(
                "INSERT INTO repository (github_repo_id, owner_login, name, visibility) VALUES (?, 'grow22', ?, 'PUBLIC') RETURNING id",
                Long.class, githubRepoId, name);

        return jdbc.queryForObject(
                "INSERT INTO user_repository (user_id, repository_id) VALUES (?, ?) RETURNING id",
                Long.class, userId, repositoryId);
    }

    @Test
    @DisplayName("처음 온 요청인 경우 QUEUED Job 을 새로 만든다")
    void firstRequestCreatesQueuedJob() {

        JobIntakeResult result = service.intake(userId, repoA, KEY_1);

        assertThat(result.created()).isTrue();
        assertThat(result.state()).isEqualTo(JobState.QUEUED);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키로 같은 레포를 다시 요청하면 기존의 Job 을 돌려준다")
    void sameKeySameRepoReturnsExistingJob() {

        JobIntakeResult first = service.intake(userId, repoA, KEY_1);
        JobIntakeResult second = service.intake(userId, repoA, KEY_1);

        assertThat(second.created()).isFalse();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키로 다른 레포를 요청하면 거절한다")
    void sameKeyOtherRepoIsRejected() {

        service.intake(userId, repoA, KEY_1);

        assertThatThrownBy(() -> service.intake(userId, repoB, KEY_1))
                .isInstanceOf(IdempotencyKeyMismatchException.class);

        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 레포에 진행 중인 Job 이 있으면 키가 달라도 그 Job 을 돌려준다")
    void activeJobIsReturnedEvenWithOtherKey() {

        JobIntakeResult first = service.intake(userId, repoA, KEY_1);
        JobIntakeResult second = service.intake(userId, repoA, KEY_2);

        assertThat(second.created()).isFalse();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 레포의 Job 이 이미 끝났으면 새 Job 을 만드는 게 가능하다")
    void finishedJobDoesNotBlockNewJob() {

        JobIntakeResult first = service.intake(userId, repoA, KEY_1);

        AnalysisJob job = jobRepository.findByUserIdAndIdempotencyKey(userId, KEY_1).orElseThrow();
        job.start();
        job.succeed(false);
        jobRepository.save(job);

        JobIntakeResult second = service.intake(userId, repoA, KEY_2);

        assertThat(second.created()).isTrue();
        assertThat(second.jobId()).isNotEqualTo(first.jobId());
        assertThat(jobRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("키가 없이 와도 서버가 키를 만들어 저장하고, 같은 레포 중복은 막는다")
    void missingKeyIsGeneratedAndDuplicateIsBlocked() {

        JobIntakeResult first = service.intake(userId, repoA, null);
        JobIntakeResult second = service.intake(userId, repoA, null);

        assertThat(first.created()).isTrue();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(jobRepository.findAll().get(0).getIdempotencyKey()).isNotBlank();
    }

    @Test
    @DisplayName("같은 레포에 요청이 동시에 몰려도 Job 은 하나만 생기고 모두 같은 Job 을 받는다")
    void concurrentRequestsShareOneJob() throws Exception {

        int requests = 8;
        ExecutorService pool = newFixedThreadPool(requests);
        CountDownLatch startLine = new CountDownLatch(1);

        List<Future<JobIntakeResult>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String key = UUID.randomUUID().toString();
            futures.add(pool.submit(() -> {
                startLine.await();
                return service.intake(userId, repoA, key);
            }));
        }
        startLine.countDown();

        List<JobIntakeResult> results = new ArrayList<>();
        for (Future<JobIntakeResult> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results).extracting(JobIntakeResult::jobId).containsOnly(results.get(0).jobId());
        assertThat(results).filteredOn(JobIntakeResult::created).hasSize(1);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

}
