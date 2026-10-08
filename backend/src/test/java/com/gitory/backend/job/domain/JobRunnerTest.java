package com.gitory.backend.job.domain;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.ingest.domain.ActivityStoreService;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.IngestRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({JobRunner.class, JobCancelService.class, ActivityStoreService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobRunnerTest {

    private static final String KEY_1 = "11111111-1111-4111-8111-111111111111";
    private static final String KEY_2 = "22222222-2222-4222-8222-222222222222";
    private static final String SHA = "a".repeat(40);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JobRunner runner;

    @Autowired
    AnalysisJobRepository jobs;

    @Autowired
    JobCancelService cancelService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @MockitoBean
    RepositoryActivityPort activities;

    @MockitoBean
    AuditLog auditLog;

    private TestFixtures fixtures;
    private Long userId;
    private Long userRepositoryId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        userRepositoryId = connectRepository(100L, "gitory");

    }

    @Test
    @DisplayName("대기 중인 Job 은 오래된 것부터 한 번에 하나만 꺼낸다")
    void runsOldestQueuedJobFirst() {

        Long newer = enqueue(connectRepository(200L, "newer"), KEY_2);
        Long older = enqueue(userRepositoryId, KEY_1);
        jdbc.update("UPDATE analysis_job SET started_at = now() - interval '1 minute' WHERE id = ?", older);
        given(activities.collect(any())).willReturn(activity(null));

        runner.runNext();

        assertThat(jobs.findById(older).orElseThrow().getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(jobs.findById(newer).orElseThrow().getState()).isEqualTo(JobState.QUEUED);

    }

    @Test
    @DisplayName("워커 여러 개가 동시에 돌아도 같은 Job 의 AI 수집은 한 번만 부른다")
    void concurrentRunnersRunJobOnce() throws Exception {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        int workers = 4;
        ExecutorService pool = newFixedThreadPool(workers);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                runner.runNext();
                return null;
            }));
        }
        startLine.countDown();

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        verify(activities, times(1)).collect(any());
        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(JobState.SUCCEEDED);

    }

    @Test
    @DisplayName("대기 중인 Job 이 없으면 아무것도 하지 않는다")
    void doesNothingWithoutQueuedJob() {

        assertThat(runner.runNext()).isFalse();
        verifyNoInteractions(activities);

    }

    @Test
    @DisplayName("AI 를 부르기 전에 Job 상태와 COMMITS 단계를 RUNNING 으로 DB 에 저장한다")
    void commitsRunningBeforeCallingAi() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        AtomicReference<String> stateDuringCall = new AtomicReference<>();
        AtomicReference<String> firstStepDuringCall = new AtomicReference<>();
        given(activities.collect(any())).willAnswer(invocation -> {
            stateDuringCall.set(jdbc.queryForObject("SELECT state FROM analysis_job WHERE id = ?", String.class, jobId));
            firstStepDuringCall.set(jdbc.queryForObject(
                    "SELECT steps -> 0 ->> 'state' FROM analysis_job WHERE id = ?", String.class, jobId));
            return activity(null);
        });

        runner.runNext();

        assertThat(stateDuringCall.get()).isEqualTo("RUNNING");
        assertThat(firstStepDuringCall.get()).isEqualTo("RUNNING");

    }

    @Test
    @DisplayName("AI 에 그 Job 의 연결 저장소를 기본 브랜치·전체 기간으로 수집 요청한다")
    void requestsDefaultBranchFullHistory() {

        enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runner.runNext();

        verify(activities).collect(new IngestRequest(userRepositoryId, List.of(), null));

    }

    @Test
    @DisplayName("수집에 성공하면 결과를 저장하고 Job 을 그 수집 기록과 연결해 SUCCEEDED 로 끝낸다")
    void storesActivityAndSucceeds() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runner.runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        Long collectionRunId = jdbc.queryForObject("SELECT id FROM collection_run", Long.class);
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.getErrorCode()).isNull();
        assertThat(job.getCollectionRunId()).isEqualTo(collectionRunId);
        assertThat(countOf("git_commit")).isEqualTo(1);

    }

    @Test
    @DisplayName("성공하면 커밋·PR 읽기 단계는 읽은 개수로 완료되고, 아직 없는 후보 추리기·추천 이유 단계는 SKIPPED 상태로 나타낸다")
    void marksStepsAfterSuccess() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runner.runNext();

        assertThat(jobs.findById(jobId).orElseThrow().getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 1, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 1, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    @Test
    @DisplayName("GitHub 한도로 일부만 읽으면 부분 완료와 GITHUB_RATE_LIMITED 를 남긴다")
    void rateLimitedCollectionIsPartialWithReason() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED));

        runner.runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);

    }

    @Test
    @DisplayName("우리 상한까지만 읽으면 부분 완료로 끝내고 에러 코드는 남기지 않는다")
    void cappedCollectionIsPartialWithoutReason() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.CAP_EXCEEDED));

        runner.runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isNull();

    }

    @ParameterizedTest
    @ValueSource(strings = {"GITHUB_API_ERROR", "RESOURCE_NOT_FOUND", "GITHUB_CONNECTION_UNAVAILABLE"})
    @DisplayName("GitHub 쪽 원인으로 수집에 실패하면 GITHUB_UNAVAILABLE 로 끝낸다")
    void githubFailureEndsAsGithubUnavailable(String cause) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willThrow(new AiClientException(cause, false, null));

        runner.runNext();

        assertFailed(jobId, JobErrorCode.GITHUB_UNAVAILABLE);

    }

    @ParameterizedTest
    @ValueSource(strings = {"AI_UNAVAILABLE", "AI_INVALID_RESPONSE", "COLLECTION_TARGET_NOT_FOUND"})
    @DisplayName("AI 나 우리 쪽 원인으로 수집에 실패하면 INTERNAL_ERROR 로 끝낸다")
    void otherFailureEndsAsInternalError(String cause) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willThrow(new AiClientException(cause, true, null));

        runner.runNext();

        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("결과를 저장하다 실패하면 저장한 것을 되돌리고 INTERNAL_ERROR 로 끝낸다")
    void storeFailureRollsBackAndEndsAsInternalError() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(new CollectedActivity(
                List.of(commit("a".repeat(41))), List.of(), List.of(), null));

        runner.runNext();

        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);
        assertThat(countOf("collection_run")).isZero();
        assertThat(jobs.findById(jobId).orElseThrow().getCollectionRunId()).isNull();

    }

    @Test
    @DisplayName("RUNNING 이 된 지 10분이 넘은 Job 은 INTERNAL_ERROR 로 끝낸다")
    void failsJobStuckInRunning() {

        Long jobId = runningSince(userRepositoryId, "11 minutes");

        runner.failStuckJobs();

        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("워커가 Job 을 성공으로 마무리하는 사이에 정리가 돌아도 성공 결과를 덮어쓰지 않는다")
    void stuckSweepDoesNotOverwriteFinishingJob() throws Exception {

        Long jobId = runningSince(userRepositoryId, "11 minutes");
        Long collectionRunId = jdbc.queryForObject(
                "INSERT INTO collection_run (user_repository_id) VALUES (?) RETURNING id", Long.class, userRepositoryId);
        CountDownLatch locked = new CountDownLatch(1);

        ExecutorService worker = newFixedThreadPool(1);
        Future<?> finishing = worker.submit(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE analysis_job SET state = 'SUCCEEDED', collection_run_id = ?, finished_at = now() WHERE id = ?",
                    collectionRunId, jobId);
            locked.countDown();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
        }));
        locked.await(5, TimeUnit.SECONDS);

        runner.failStuckJobs();

        finishing.get(10, TimeUnit.SECONDS);
        worker.shutdown();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.getCollectionRunId()).isEqualTo(collectionRunId);

    }

    @Test
    @DisplayName("RUNNING 이 된 지 10분이 안 된 Job 은 그대로 둔다")
    void keepsRecentlyRunningJob() {

        Long jobId = runningSince(userRepositoryId, "9 minutes");

        runner.failStuckJobs();

        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("취소된 QUEUED Job 은 워커가 꺼내지 않는다")
    void doesNotRunCanceledJob() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        cancel(jobId);

        assertThat(runner.runNext()).isFalse();
        verifyNoInteractions(activities);

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 취소되면 수집 결과를 저장하지 않고 CANCELED 로 둔다")
    void discardsResultWhenCanceledDuringCall() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willAnswer(invocation -> {
            cancel(jobId);
            return activity(null);
        });

        runner.runNext();

        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(JobState.CANCELED);
        assertThat(countOf("collection_run")).isZero();
        assertThat(countOf("git_commit")).isZero();

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 취소되면 AI 가 실패해도 FAILED 로 덮어쓰지 않는다")
    void keepsCanceledWhenCallFailsAfterCancel() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willAnswer(invocation -> {
            cancel(jobId);
            throw new AiClientException("AI_UNAVAILABLE", true, null);
        });

        runner.runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.CANCELED);
        assertThat(job.getErrorCode()).isNull();

    }

    @Test
    @DisplayName("취소와 워커 마무리가 겹치면 워커는 취소가 끝날 때까지 기다렸다가 결과를 버린다")
    void workerWaitsForCancelToFinish() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        CountDownLatch cancelHoldsLock = new CountDownLatch(1);
        ExecutorService canceller = newSingleThreadExecutor();
        given(activities.collect(any())).willAnswer(invocation -> {
            canceller.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM analysis_job WHERE id = ? FOR UPDATE", Long.class, jobId);
                cancelHoldsLock.countDown();
                pause(300);
                jdbc.update("UPDATE analysis_job SET state = 'CANCELED', finished_at = now() WHERE id = ?", jobId);
            }));
            cancelHoldsLock.await(5, TimeUnit.SECONDS);
            return activity(null);
        });

        runner.runNext();
        canceller.shutdown();

        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(JobState.CANCELED);
        assertThat(countOf("collection_run")).isZero();

    }

    private void cancel(Long jobId) {

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        cancelService.cancel(job.getPublicId(), job.getUserId());

    }

    private static void pause(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private Long connectRepository(Long githubRepoId, String name) {

        Long repositoryId = fixtures.insertRepository(githubRepoId, "grow22", name);
        return fixtures.insertUserRepository(userId, repositoryId);

    }

    private Long enqueue(Long connectedRepositoryId, String key) {

        return jobs.save(AnalysisJob.enqueue(userId, connectedRepositoryId, key)).getId();

    }

    private Long runningSince(Long connectedRepositoryId, String elapsed) {

        Long jobId = enqueue(connectedRepositoryId, KEY_1);
        jdbc.update("UPDATE analysis_job SET state = 'RUNNING', updated_at = now() - ?::interval WHERE id = ?",
                elapsed, jobId);
        return jobId;

    }

    private void assertFailed(Long jobId, JobErrorCode errorCode) {

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(errorCode);

    }

    private static CollectedActivity activity(PartialReason partialReason) {

        return new CollectedActivity(List.of(commit(SHA)), List.of(new CollectedPullRequest(7)), List.of(),
                partialReason);

    }

    private static CollectedCommit commit(String sha) {

        return new CollectedCommit(sha, "grow22", "Grow22", "feat: 워커 추가", Instant.parse("2026-10-01T00:00:00Z"),
                10, 2, 1, (short) 1, null);

    }

    private int countOf(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }
}
