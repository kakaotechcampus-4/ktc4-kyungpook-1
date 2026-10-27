package com.gitory.backend.job.domain;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.agent.port.ExperienceGroupingPort;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.common.infra.ai.AiProgress;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ActivityStoreService;
import com.gitory.backend.ingest.domain.CollectionStatsService;
import com.gitory.backend.ingest.domain.GithubState;
import com.gitory.backend.ingest.domain.GroupingMaterialService;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.IngestRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateEmptyReasons;
import com.gitory.backend.recommend.domain.CandidateStoreService;
import com.gitory.backend.support.AiTestProperties;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

@DataJpaTest(properties = {
        "gitory.ai.collect-read-timeout=10m",
        "gitory.ai.groups-read-timeout=10m",
        "gitory.analysis.grouping.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ClockConfig.class, JobRunner.class, JobCancelService.class, ActivityStoreService.class, GroupingMaterialService.class,
        CandidateStoreService.class, StoredActivityService.class, UserLoginService.class, JobViewFactory.class,
        CandidateEmptyReasons.class, CollectionStatsService.class, GeneratedCardQueryService.class})
@EnableConfigurationProperties(AiClientProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class JobRunnerTest {

    private static final String LOGIN = "grow22";
    private static final String KEY_1 = "11111111-1111-4111-8111-111111111111";
    private static final String KEY_2 = "22222222-2222-4222-8222-222222222222";
    private static final String KEY_3 = "33333333-3333-4333-8333-333333333333";
    private static final String SHA = "a".repeat(40);
    private static final String OTHER_SHA = "b".repeat(40);
    private static final String NEW_SHA = "c".repeat(40);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JobRunner runner;

    @Autowired
    AnalysisJobRepository jobs;

    @Autowired
    ActivityStoreService activityStore;

    @Autowired
    JobCancelService cancelService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    UserLoginService users;

    @Autowired
    GroupingMaterialService materials;

    @Autowired
    CandidateStoreService candidates;

    @MockitoBean
    RepositoryActivityPort activities;

    @MockitoBean
    AuditLog auditLog;

    @MockitoBean
    ExperienceGroupingPort grouping;

    @MockitoBean
    GithubCollectionAccessPort githubAccess;

    @MockitoBean
    CardDraftRunner cardDrafts;

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

        runNext();

        assertThat(jobs.findById(older).orElseThrow().getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(jobs.findById(newer).orElseThrow().getState()).isEqualTo(JobState.QUEUED);

    }

    @Test
    @DisplayName("실시간 진행을 켜면 진행을 받는 수집을 불러, 수집이 끝나기 전에 받은 진행이 Job 단계에 남는다")
    void recordsCollectionProgressWhileCollecting() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        AtomicReference<List<JobStep>> duringCollection = new AtomicReference<>();
        given(activities.collect(any(), any())).willAnswer(invocation -> {
            Consumer<AiProgress> progress = invocation.getArgument(1);
            progress.accept(new AiProgress("PR_REVIEW", 2, 3));
            duringCollection.set(jobs.findById(jobId).orElseThrow().getSteps());
            return activity(null);
        });
        JobRunner live = new JobRunner(jobs, activities, activityStore, transaction, AiTestProperties.defaults().build(),
                new JobGroupingProperties(false), users, materials, candidates, grouping, githubAccess,
                new JobDraftProperties(false), null, new JobProgressProperties(true), Clock.systemUTC());

        live.claimNext().ifPresent(live::run);

        assertThat(duringCollection.get().getFirst()).isEqualTo(new JobStep(JobStepKey.PR_REVIEW, JobStepState.RUNNING, 2, 3));
        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(JobState.SUCCEEDED);
        verify(activities, never()).collect(any());

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
                runNext();
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

        assertThat(runNext()).isFalse();
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

        runNext();

        assertThat(stateDuringCall.get()).isEqualTo("RUNNING");
        assertThat(firstStepDuringCall.get()).isEqualTo("RUNNING");

    }

    @Test
    @DisplayName("AI 에 그 Job 의 연결 저장소를 기본 브랜치·전체 기간으로 수집 요청한다")
    void requestsDefaultBranchFullHistory() {

        enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runNext();

        verify(activities).collect(new IngestRequest(userRepositoryId, List.of(), List.of()));

    }

    @Test
    @DisplayName("재수집은 저장된 commit SHA를 전달한다")
    void requestsKnownCommits() {

        IngestRequest initial = new IngestRequest(userRepositoryId, List.of(), List.of());
        activityStore.store(initial, activity(null));
        activityStore.store(initial, partialActivityOf(OTHER_SHA));

        enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runNext();

        verify(activities).collect(new IngestRequest(userRepositoryId, List.of(), List.of(OTHER_SHA, SHA)));

    }

    @Test
    @DisplayName("수집에 성공하면 결과를 저장하고 Job 을 그 수집 기록과 연결해 SUCCEEDED 로 끝낸다")
    void storesActivityAndSucceeds() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        Long collectionRunId = jdbc.queryForObject("SELECT id FROM collection_run", Long.class);
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.getErrorCode()).isNull();
        assertThat(job.getCollectionRunId()).isEqualTo(collectionRunId);
        assertThat(countOf("git_commit")).isEqualTo(1);

    }

    @Test
    @DisplayName("성공하면 PR·커밋 읽기 단계는 이번에 읽은 수(읽은 수 = 전체)로 완료되고, 경험 그룹화를 꺼 두면 후보 추리기·추천 이유 단계는 SKIPPED 상태로 나타낸다")
    void marksStepsAfterSuccess() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runNext();

        assertThat(jobs.findById(jobId).orElseThrow().getSteps()).containsExactly(
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 1, 1),
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 1, 1),
                new JobStep(JobStepKey.COMPRESS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    @Test
    @DisplayName("다시 분석하면 커밋 단계는 저장소에 쌓인 커밋 수가 아니라 이번에 새로 읽은 커밋 수로 완료된다")
    void commitsStepCountsNewCommitsOnReanalysis() {

        IngestRequest initial = new IngestRequest(userRepositoryId, List.of(), List.of());
        activityStore.store(initial, activity(null));
        activityStore.store(initial, partialActivityOf(OTHER_SHA));
        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(newCommitActivity(NEW_SHA, null));

        runNext();

        assertThat(jobs.findById(jobId).orElseThrow().getSteps()).containsExactly(
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 0, 0),
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 1, 1),
                new JobStep(JobStepKey.COMPRESS, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));

    }

    @Test
    @DisplayName("일부만 읽고 끝난 재분석도 커밋 단계는 이번에 새로 읽은 커밋 수로 완료된다")
    void commitsStepCountsNewCommitsOnPartialReanalysis() {

        activityStore.store(new IngestRequest(userRepositoryId, List.of(), List.of()), activity(null));
        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(newCommitActivity(NEW_SHA, PartialReason.CAP_EXCEEDED));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getSteps().get(1)).isEqualTo(new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 1, 1));

    }

    @Test
    @DisplayName("경험 그룹화를 꺼 두면 AI 경험 그룹화를 부르지 않고 후보 추리기·추천 이유를 SKIPPED 로 둔 채 끝내며 판정도 비운다")
    void skipsGroupingWhenSwitchedOff() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        verifyNoInteractions(grouping);
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.getSteps()).extracting(JobStep::key, JobStep::state).endsWith(
                tuple(JobStepKey.COMPRESS, JobStepState.SKIPPED), tuple(JobStepKey.REASON, JobStepState.SKIPPED));
        assertThat(JobView.from(job, UUID.randomUUID(), List.of(), List::of).result().verdict()).isNull();
        assertThat(countOf("candidate_grouping")).isZero();

    }

    @Test
    @DisplayName("GitHub 한도로 일부만 읽으면 부분 완료와 GITHUB_RATE_LIMITED 를 남긴다")
    void rateLimitedCollectionIsPartialWithReason() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);

    }

    @Test
    @DisplayName("GitHub 한도로 일부만 읽으면 그 Job 주인의 한도가 풀리는 시각까지 남은 초를 남기고 감사 기록은 남기지 않는다")
    void rateLimitedCollectionKeepsRetryAfterSec() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED));
        given(githubAccess.rateLimitResetAt(userId)).willReturn(Optional.of(Instant.now().plusSeconds(600)));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);
        assertThat(job.getRetryAfterSec()).isBetween(590, 600);
        verifyNoInteractions(auditLog);

    }

    @Test
    @DisplayName("한도가 풀리는 시각을 알아내지 못해도 부분 완료·GITHUB_RATE_LIMITED 는 그대로이고 남은 초만 비어 있다")
    void rateLimitedCollectionWithoutResetKeepsResult() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED));
        given(githubAccess.rateLimitResetAt(userId)).willReturn(Optional.empty());

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);
        assertThat(job.getRetryAfterSec()).isNull();
        assertThat(countOf("collection_run")).isEqualTo(1);

    }

    @Test
    @DisplayName("한도가 풀리는 시각은 DB 트랜잭션 밖에서 묻는다")
    void asksRateLimitOutsideTransaction() {

        enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED));
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        given(githubAccess.rateLimitResetAt(userId)).willAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.empty();
        });

        runNext();

        assertThat(transactionActive.get()).isFalse();

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 취소된 Job 은 한도를 물었어도 남은 초 없이 CANCELED 로 두고 수집 결과도 저장하지 않는다")
    void canceledJobKeepsNoRetryAfterSec() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willAnswer(invocation -> {
            cancel(jobId);
            return activity(PartialReason.GITHUB_RATE_LIMITED);
        });
        given(githubAccess.rateLimitResetAt(userId)).willReturn(Optional.of(Instant.now().plusSeconds(600)));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.CANCELED);
        assertThat(job.getRetryAfterSec()).isNull();
        assertThat(countOf("collection_run")).isZero();

    }

    @ParameterizedTest
    @EnumSource(value = PartialReason.class, names = {"CAP_EXCEEDED", "TIMEOUT"})
    @DisplayName("GitHub 한도가 아닌 이유로 일부만 읽은 수집은 한도를 묻지 않는다")
    void otherPartialReasonsDoNotAskRateLimit(PartialReason reason) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(reason));

        runNext();

        verifyNoInteractions(githubAccess);
        assertThat(jobs.findById(jobId).orElseThrow().getRetryAfterSec()).isNull();

    }

    @Test
    @DisplayName("끝까지 읽은 수집은 한도를 묻지 않는다")
    void completeCollectionDoesNotAskRateLimit() {

        enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(null));

        runNext();

        verifyNoInteractions(githubAccess);

    }

    @Test
    @DisplayName("우리 상한까지만 읽으면 부분 완료와 COLLECTION_CAPPED 를 남긴다")
    void cappedCollectionIsPartialWithCappedCode() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.CAP_EXCEEDED));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.COLLECTION_CAPPED);

    }

    @ParameterizedTest
    @CsvSource({"GITHUB_API_ERROR, true, 502", "RESOURCE_NOT_FOUND, false, 404", "GITHUB_CONNECTION_UNAVAILABLE, false, "})
    @DisplayName("GitHub 쪽 원인으로 수집에 실패하면 AI 가 다시 해 볼 만하다고 알려도 GITHUB_UNAVAILABLE 로 끝낸다")
    void githubFailureEndsAsGithubUnavailable(String cause, boolean retryable, Integer status) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willThrow(new AiClientException(cause, retryable, status));

        runNext();

        assertFailed(jobId, JobErrorCode.GITHUB_UNAVAILABLE);

    }

    @Test
    @DisplayName("GitHub 이 토큰을 거절했거나 쓸 토큰이 없으면 일시 장애와 나눠 GITHUB_UNAUTHORIZED 로 끝낸다")
    void rejectedTokenEndsAsGithubUnauthorized() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willThrow(new AiClientException("GITHUB_UNAUTHORIZED", false, 401));

        runNext();

        assertFailed(jobId, JobErrorCode.GITHUB_UNAUTHORIZED);

    }

    @ParameterizedTest
    @CsvSource({"AI_UNAVAILABLE, true, ", "AI_UNAVAILABLE, true, 500", "LLM_UNAVAILABLE, true, 503", "AI_HTTP_ERROR, true, 502"})
    @DisplayName("AI 와 연결이 안 되거나 시간이 지나거나 AI 가 잠시 못 한다고 알리면 수집 실패도 AI_UNAVAILABLE 로 끝낸다")
    void temporaryAiFailureEndsAsAiUnavailable(String cause, boolean retryable, Integer status) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willThrow(new AiClientException(cause, retryable, status));

        runNext();

        assertFailed(jobId, JobErrorCode.AI_UNAVAILABLE);

    }

    @ParameterizedTest
    @CsvSource({"AI_INVALID_RESPONSE, false, ", "COLLECTION_TARGET_NOT_FOUND, false, ", "INVALID_PAYLOAD, true, 400",
            "INTERNAL_ERROR, true, 500"})
    @DisplayName("다시 해도 소용없는 AI 실패나 우리 쪽 원인의 수집 실패는 INTERNAL_ERROR 로 끝낸다")
    void permanentFailureEndsAsInternalError(String cause, boolean retryable, Integer status) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willThrow(new AiClientException(cause, retryable, status));

        runNext();

        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("결과를 저장하다 실패하면 저장한 것을 되돌리고 INTERNAL_ERROR 로 끝낸다")
    void storeFailureRollsBackAndEndsAsInternalError() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willReturn(new CollectedActivity(
                List.of(commit("a".repeat(41))), List.of(), List.of(), null, null));

        runNext();

        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);
        assertThat(countOf("collection_run")).isZero();
        assertThat(jobs.findById(jobId).orElseThrow().getCollectionRunId()).isNull();

    }

    @Test
    @DisplayName("수집·경험 그룹화·카드 초안 응답 대기 한도 중 큰 값보다 5분 넘게 RUNNING 인 Job 은 INTERNAL_ERROR 로 끝낸다")
    void failsJobStuckInRunning() {

        Long jobId = runningSince(userRepositoryId, "16 minutes");

        runner.failStuckJobs();

        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("워커가 Job 을 성공으로 마무리하는 사이에 정리가 돌아도 성공 결과를 덮어쓰지 않는다")
    void stuckSweepDoesNotOverwriteFinishingJob() throws Exception {

        Long jobId = runningSince(userRepositoryId, "16 minutes");
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
    @DisplayName("RUNNING 인 시간이 수집·경험 그룹화·카드 초안 응답 대기 한도 중 큰 값에 5분을 더한 시간보다 짧은 Job 은 그대로 둔다")
    void keepsRecentlyRunningJob() {

        Long jobId = runningSince(userRepositoryId, "14 minutes");

        runner.failStuckJobs();

        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("Job 을 꺼내 시작하면 접수 뒤 기다린 초를 INFO 로그로 남긴다")
    void logsWaitSecondsWhenClaimed(CapturedOutput output) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        jdbc.update("UPDATE analysis_job SET started_at = ? WHERE id = ?", secondsAgo(100), jobId);

        runner.claimNext();

        String line = logLineOf(output, "분석 Job " + jobId + " 을 접수 ");
        assertThat(line).contains("INFO");
        assertThat(secondsIn(line, "접수 (\\d+)초 뒤에 시작한다")).isBetween(100L, 160L);

    }

    @Test
    @DisplayName("멈춘 Job 을 정리하면 진행 중이던 단계와 진행이 없던 초를 WARN 로그로 남긴다")
    void logsStepAndSecondsWhenFailingStuckJob(CapturedOutput output) {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        runner.claimNext();
        jdbc.update("UPDATE analysis_job SET updated_at = ? WHERE id = ?", secondsAgo(960), jobId);

        runner.failStuckJobs();

        String line = logLineOf(output, "분석 Job " + jobId + " 을 PR_REVIEW 단계에서 ");
        assertThat(line).contains("WARN").contains("초 동안 진행이 없어 INTERNAL_ERROR 로 끝낸다");
        assertThat(secondsIn(line, "단계에서 (\\d+)초 동안")).isBetween(960L, 1020L);
        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("진행 중인 단계가 없는 멈춘 Job 도 예외 없이 정리하고 단계를 뺀 WARN 로그를 남긴다")
    void logsWithoutStepWhenNoStepIsRunning(CapturedOutput output) {

        Long jobId = runningSince(userRepositoryId, "16 minutes");
        assertThat(jobs.findById(jobId).orElseThrow().getSteps()).extracting(JobStep::state)
                .doesNotContain(JobStepState.RUNNING);

        runner.failStuckJobs();

        String line = logLineOf(output, "분석 Job " + jobId + " 을 ");
        assertThat(line).contains("WARN").doesNotContain("단계에서")
                .contains("초 동안 진행이 없어 INTERNAL_ERROR 로 끝낸다");
        assertFailed(jobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("여러 단계가 함께 진행 중인 멈춘 Job 은 그중 첫 단계를 WARN 로그에 남긴다")
    void logsFirstRunningStepWhenSeveralAreRunning(CapturedOutput output) {

        Long draftJobId = draftJob("RUNNING");
        jdbc.update("UPDATE analysis_job SET steps = ?::jsonb, updated_at = ? WHERE id = ?", """
                [{"key":"COMMITS","state":"RUNNING","done":1,"total":2},
                 {"key":"PR_REVIEW","state":"QUEUED","done":0,"total":null},
                 {"key":"COMPRESS","state":"RUNNING","done":0,"total":2},
                 {"key":"REASON","state":"QUEUED","done":0,"total":null}]""", secondsAgo(960), draftJobId);

        runner.failStuckJobs();

        assertThat(logLineOf(output, "카드 초안 Job " + draftJobId + " 을 COMMITS 단계에서 ")).contains("WARN");
        assertFailed(draftJobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("수집 응답 대기 한도를 늘리면 멈춘 Job 판정도 그만큼 늦춰진다")
    void stuckJudgementFollowsCollectReadTimeout() {

        Long jobId = runningSince(userRepositoryId, "16 minutes");
        JobRunner longerWait = runnerWith(AiTestProperties.defaults().collectReadTimeout(Duration.ofMinutes(20))
                .groupsReadTimeout(Duration.ofMinutes(10)).build());

        longerWait.failStuckJobs();

        assertThat(stateOf(jobId)).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("경험 그룹화 응답 대기 한도를 늘리면 멈춘 Job 판정도 그만큼 늦춰진다")
    void stuckJudgementFollowsGroupsReadTimeout() {

        Long jobId = runningSince(userRepositoryId, "16 minutes");
        JobRunner longerWait = runnerWith(AiTestProperties.defaults().collectReadTimeout(Duration.ofMinutes(10))
                .groupsReadTimeout(Duration.ofMinutes(20)).build());

        longerWait.failStuckJobs();

        assertThat(stateOf(jobId)).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("카드 초안의 diff 근거 응답 대기 한도를 늘리면 멈춘 Job 판정도 그만큼 늦춰진다")
    void stuckJudgementFollowsDiffEvidenceReadTimeout() {

        Long jobId = runningSince(userRepositoryId, "16 minutes");
        JobRunner longerWait = runnerWith(AiTestProperties.defaults().collectReadTimeout(Duration.ofMinutes(10))
                .groupsReadTimeout(Duration.ofMinutes(10)).diffEvidenceReadTimeout(Duration.ofMinutes(20)).build());

        longerWait.failStuckJobs();

        assertThat(stateOf(jobId)).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("카드 초안의 STAR 응답 대기 한도를 늘리면 멈춘 Job 판정도 그만큼 늦춰진다")
    void stuckJudgementFollowsStarReadTimeout() {

        Long jobId = runningSince(userRepositoryId, "16 minutes");
        JobRunner longerWait = runnerWith(AiTestProperties.defaults().collectReadTimeout(Duration.ofMinutes(10))
                .groupsReadTimeout(Duration.ofMinutes(10)).starReadTimeout(Duration.ofMinutes(20)).build());

        longerWait.failStuckJobs();

        assertThat(stateOf(jobId)).isEqualTo(JobState.RUNNING);

    }

    @Test
    @DisplayName("취소된 QUEUED Job 은 워커가 꺼내지 않는다")
    void doesNotRunCanceledJob() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        cancel(jobId);

        assertThat(runNext()).isFalse();
        verifyNoInteractions(activities);

    }

    @Test
    @DisplayName("먼저 기다리던 카드 초안 Job 은 분석 Job 보다 먼저, 오래된 순서대로 꺼낸다")
    void claimsQueuedDraftJobInOrder() {

        Long draftJobId = insertQueuedDraftJob(userRepositoryId);
        jdbc.update("UPDATE analysis_job SET started_at = now() - interval '1 minute' WHERE id = ?", draftJobId);
        Long analyzeJobId = enqueue(userRepositoryId, KEY_1);

        Optional<AnalysisJob> claimed = runner.claimNext();

        assertThat(claimed).map(AnalysisJob::getId).contains(draftJobId);
        assertThat(stateOf(draftJobId)).isEqualTo(JobState.RUNNING);
        assertThat(stateOf(analyzeJobId)).isEqualTo(JobState.QUEUED);

    }

    @Test
    @DisplayName("AI 초안을 꺼 두면 카드 초안 Job 은 카드를 채우는 러너를 부르지 않고 끝난다")
    void draftSwitchOffSkipsCardDraftRunner() {

        Long draftJobId = insertQueuedDraftJob(userRepositoryId);

        runNext();

        verifyNoInteractions(cardDrafts);
        assertThat(stateOf(draftJobId)).isEqualTo(JobState.SUCCEEDED);

    }

    @Test
    @DisplayName("카드 초안 Job 은 AI 를 부르지 않고 네 단계를 SKIPPED 로 둔 채 부분 완료와 에러 코드 없이 SUCCEEDED 로 끝낸다")
    void draftJobEndsWithoutCallingAi() {

        Long draftJobId = insertQueuedDraftJob(userRepositoryId);

        runNext();

        AnalysisJob job = jobs.findById(draftJobId).orElseThrow();
        verifyNoInteractions(activities, grouping);
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.getErrorCode()).isNull();
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getSteps()).extracting(JobStep::state).containsOnly(JobStepState.SKIPPED);
        assertThat(job.getSteps()).hasSize(4);
        assertThat(countOf("collection_run")).isZero();

    }

    @Test
    @DisplayName("실행 중인 카드 초안 Job 은 같은 사용자의 분석 Job 을 막지 않는다")
    void runningDraftJobDoesNotBlockAnalyzeJob() {

        draftJob("RUNNING");
        Long analyzeJobId = enqueue(userRepositoryId, KEY_1);

        assertThat(runner.claimNext()).map(AnalysisJob::getId).contains(analyzeJobId);

    }

    @Test
    @DisplayName("실행 중인 분석 Job 은 같은 사용자의 카드 초안 Job 을 막지 않는다")
    void runningAnalyzeJobDoesNotBlockDraftJob() {

        runningSince(userRepositoryId, "1 minute");
        Long draftJobId = draftJob("QUEUED");

        assertThat(runner.claimNext()).map(AnalysisJob::getId).contains(draftJobId);

    }

    @Test
    @DisplayName("실행 중인 분석 Job 이 있으면 그 사용자의 다른 분석 Job 은 여전히 기다린다")
    void runningAnalyzeJobStillBlocksSameUsersAnalyzeJob() {

        runningSince(userRepositoryId, "1 minute");
        enqueue(connectRepository(200L, "newer"), KEY_2);

        assertThat(runner.claimNext()).isEmpty();

    }

    @Test
    @DisplayName("실행 중인 카드 초안 Job 이 있으면 그 사용자의 다른 카드 초안 Job 은 기다린다")
    void runningDraftJobBlocksSameUsersDraftJob() {

        draftJob("RUNNING");
        Long queuedDraftJobId = draftJob(connectRepository(200L, "newer"), "QUEUED");

        assertThat(runner.claimNext()).isEmpty();
        assertThat(stateOf(queuedDraftJobId)).isEqualTo(JobState.QUEUED);

    }

    @Test
    @DisplayName("실행 중인 카드 초안 Job 때문에 기다리는 오래된 카드 초안 Job 은 건너뛰고 다른 사용자의 카드 초안 Job 을 꺼낸다")
    void skipsBlockedDraftJobAndClaimsOtherUsersDraftJob() {

        draftJob("RUNNING");
        Long sameUser = draftJob(connectRepository(200L, "same-user"), "QUEUED");
        jdbc.update("UPDATE analysis_job SET started_at = now() - interval '1 minute' WHERE id = ?", sameUser);
        Long otherUser = draftJobForOtherUser();

        Optional<AnalysisJob> claimed = runner.claimNext();

        assertThat(claimed).map(AnalysisJob::getId).contains(otherUser);
        assertThat(stateOf(sameUser)).isEqualTo(JobState.QUEUED);

    }

    @Test
    @DisplayName("같은 사용자의 카드 초안 Job 둘이 대기 중이면 먼저 접수된 것을 꺼낸다")
    void claimsOlderDraftJobWhenSameUserHasTwoQueued() {

        Long older = draftJob("QUEUED");
        jdbc.update("UPDATE analysis_job SET started_at = now() - interval '1 minute' WHERE id = ?", older);
        draftJob(connectRepository(200L, "newer"), "QUEUED");

        assertThat(runner.claimNext()).map(AnalysisJob::getId).contains(older);

    }

    @Test
    @DisplayName("서버가 다시 뜨면서 QUEUED 로 되돌린 카드 초안 Job 은 다시 돌아도 같은 결과로 끝난다")
    void requeuedDraftJobEndsWithSameResult() {

        Long draftJobId = insertQueuedDraftJob(userRepositoryId);
        runner.claimNext();
        runner.requeueRunningJobs();
        assertThat(stateOf(draftJobId)).isEqualTo(JobState.QUEUED);

        runNext();

        AnalysisJob job = jobs.findById(draftJobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.getSteps()).extracting(JobStep::state).containsOnly(JobStepState.SKIPPED);
        assertThat(job.getErrorCode()).isNull();

    }

    @Test
    @DisplayName("취소된 QUEUED 카드 초안 Job 은 워커가 꺼내지 않는다")
    void doesNotRunCanceledDraftJob() {

        Long draftJobId = insertQueuedDraftJob(userRepositoryId);
        cancel(draftJobId);

        assertThat(runNext()).isFalse();
        assertThat(stateOf(draftJobId)).isEqualTo(JobState.CANCELED);

    }

    @Test
    @DisplayName("꺼낸 뒤 실행 전에 취소된 카드 초안 Job 은 SUCCEEDED 로 덮어쓰지 않고 CANCELED 로 둔다")
    void keepsCanceledDraftJobCanceled(CapturedOutput output) {

        Long draftJobId = insertQueuedDraftJob(userRepositoryId);
        AnalysisJob claimed = runner.claimNext().orElseThrow();
        cancel(draftJobId);

        runner.run(claimed);

        assertThat(stateOf(draftJobId)).isEqualTo(JobState.CANCELED);
        assertThat(jobs.findById(draftJobId).orElseThrow().getSteps()).extracting(JobStep::state)
                .doesNotContain(JobStepState.DONE);
        assertThat(output).doesNotContain("로 끝낸다");

    }

    @Test
    @DisplayName("멈춘 카드 초안 Job 도 분석 Job 과 같은 기준으로 INTERNAL_ERROR 로 끝낸다")
    void failsStuckDraftJob() {

        Long draftJobId = draftJob("RUNNING");
        jdbc.update("UPDATE analysis_job SET updated_at = now() - interval '16 minutes' WHERE id = ?", draftJobId);

        runner.failStuckJobs();

        assertFailed(draftJobId, JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("AI 를 기다리는 사이 취소되면 수집 결과를 저장하지 않고 CANCELED 로 둔다")
    void discardsResultWhenCanceledDuringCall() {

        Long jobId = enqueue(userRepositoryId, KEY_1);
        given(activities.collect(any())).willAnswer(invocation -> {
            cancel(jobId);
            return activity(null);
        });

        runNext();

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

        runNext();

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

        runNext();
        canceller.shutdown();

        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(JobState.CANCELED);
        assertThat(countOf("collection_run")).isZero();

    }

    @Test
    @DisplayName("같은 사용자의 Job 이 실행 중이면 그 사용자의 대기 Job 은 건너뛰고 다른 사용자의 Job 을 꺼낸다")
    void skipsUserWithRunningJob() {

        runningSince(userRepositoryId, "1 minute");
        Long sameUser = enqueue(connectRepository(200L, "same-user"), KEY_2);
        jdbc.update("UPDATE analysis_job SET started_at = now() - interval '1 minute' WHERE id = ?", sameUser);
        Long otherUser = enqueueForOtherUser();

        Optional<AnalysisJob> claimed = runner.claimNext();

        assertThat(claimed).map(AnalysisJob::getId).contains(otherUser);
        assertThat(stateOf(sameUser)).isEqualTo(JobState.QUEUED);

    }

    @Test
    @DisplayName("같은 사용자의 앞 Job 이 끝나면 그 사용자의 다음 Job 을 꺼낸다")
    void claimsSameUserJobAfterPreviousFinishes() {

        enqueue(userRepositoryId, KEY_1);
        enqueue(connectRepository(200L, "newer"), KEY_2);
        given(activities.collect(any())).willReturn(activity(null));

        runNext();

        assertThat(runner.claimNext()).isPresent();

    }

    @Test
    @DisplayName("같은 사용자의 Job 둘이 대기 중이면 워커는 빈 자리가 남아도 하나만 RUNNING 으로 꺼낸다")
    void workerRunsOneJobPerUserAtATime() {

        Long first = enqueue(userRepositoryId, KEY_1);
        Long second = enqueue(connectRepository(200L, "newer"), KEY_2);
        CountDownLatch release = new CountDownLatch(1);
        given(activities.collect(any())).willAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return activity(null);
        });
        JobWorker worker = new JobWorker(runner, new JobWorkerProperties(3));

        try {
            worker.work();

            assertThat(List.of(stateOf(first), stateOf(second)))
                    .containsExactlyInAnyOrder(JobState.RUNNING, JobState.QUEUED);
        } finally {
            release.countDown();
            await().atMost(5, TimeUnit.SECONDS).until(() -> runningJobCount() == 0);
        }

    }

    @Test
    @DisplayName("서버가 뜰 때 남아 있던 RUNNING Job 만 QUEUED 로 되돌리고 접수 시각은 그대로 둔다")
    void requeuesOnlyRunningJobs() {

        Long running = enqueue(userRepositoryId, KEY_1);
        runner.claimNext();
        Long finished = enqueue(connectRepository(200L, "finished"), KEY_2);
        jdbc.update("UPDATE analysis_job SET state = 'SUCCEEDED', finished_at = now() WHERE id = ?", finished);
        Instant acceptedAt = jobs.findById(running).orElseThrow().getStartedAt();

        runner.requeueRunningJobs();

        AnalysisJob requeued = jobs.findById(running).orElseThrow();
        assertThat(requeued.getState()).isEqualTo(JobState.QUEUED);
        assertThat(requeued.getSteps()).extracting(JobStep::state).containsOnly(JobStepState.QUEUED);
        assertThat(requeued.getStartedAt()).isEqualTo(acceptedAt);
        assertThat(stateOf(finished)).isEqualTo(JobState.SUCCEEDED);

    }

    @Test
    @DisplayName("서버가 다시 떠서 실행 중이던 카드 초안 Job 을 대기열에 되돌리면 INFO 로그를 남긴다")
    void logsRequeuedDraftJob(CapturedOutput output) {

        Long draftJobId = draftJob("RUNNING");

        runner.requeueRunningJobs();

        assertThat(stateOf(draftJobId)).isEqualTo(JobState.QUEUED);
        assertThat(logLineOf(output, "서버가 다시 떠서 카드 초안 Job " + draftJobId + " 을 대기열에 되돌린다"))
                .contains("INFO");

    }

    private boolean runNext() {

        Optional<AnalysisJob> claimed = runner.claimNext();
        claimed.ifPresent(runner::run);
        return claimed.isPresent();

    }

    private void cancel(Long jobId) {

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        cancelService.cancel(job.getPublicId(), job.getUserId(), LOGIN);

    }

    private JobRunner runnerWith(AiClientProperties aiProperties) {

        return new JobRunner(jobs, activities, activityStore, transaction, aiProperties,
                new JobGroupingProperties(false), users, materials, candidates, grouping, githubAccess,
                new JobDraftProperties(false), null, new JobProgressProperties(false), Clock.systemUTC());

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

    private Long enqueueForOtherUser() {

        Long otherUserId = fixtures.insertUser(2L, "teammate");
        Long repositoryId = fixtures.insertRepository(300L, "teammate", "app");
        Long connectedRepositoryId = fixtures.insertUserRepository(otherUserId, repositoryId);
        return jobs.save(AnalysisJob.enqueue(otherUserId, connectedRepositoryId, KEY_3)).getId();

    }

    private JobState stateOf(Long jobId) {

        return jobs.findById(jobId).orElseThrow().getState();

    }

    private int runningJobCount() {

        return jdbc.queryForObject("SELECT count(*) FROM analysis_job WHERE state = 'RUNNING'", Integer.class);

    }

    private Long runningSince(Long connectedRepositoryId, String elapsed) {

        Long jobId = enqueue(connectedRepositoryId, KEY_1);
        jdbc.update("UPDATE analysis_job SET state = 'RUNNING', updated_at = now() - ?::interval WHERE id = ?",
                elapsed, jobId);
        return jobId;

    }

    private Long draftJob(String state) {

        return draftJob(userRepositoryId, state);

    }

    private Long draftJob(Long connectedRepositoryId, String state) {

        UUID publicId = new CandidateCardFixtures(jdbc).insertDraftJob(userId, connectedRepositoryId, state);
        return jobs.findByPublicId(publicId).orElseThrow().getId();

    }

    private Long draftJobForOtherUser() {

        Long otherUserId = fixtures.insertUser(2L, "teammate");
        Long repositoryId = fixtures.insertRepository(300L, "teammate", "app");
        Long connectedRepositoryId = fixtures.insertUserRepository(otherUserId, repositoryId);
        UUID publicId = new CandidateCardFixtures(jdbc).insertDraftJob(otherUserId, connectedRepositoryId, "QUEUED");
        return jobs.findByPublicId(publicId).orElseThrow().getId();

    }

    private Long insertQueuedDraftJob(Long connectedRepositoryId) {

        return jdbc.queryForObject(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, 'DRAFT', 'QUEUED') RETURNING id",
                Long.class, userId, connectedRepositoryId, KEY_2);

    }

    private void assertFailed(Long jobId, JobErrorCode errorCode) {

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(errorCode);

    }

    private static String logLineOf(CapturedOutput output, String message) {

        return output.getAll().lines().filter(line -> line.contains(message)).findFirst().orElse("");

    }

    private static OffsetDateTime secondsAgo(long seconds) {

        return OffsetDateTime.ofInstant(Instant.now().minusSeconds(seconds), ZoneOffset.UTC);

    }

    private static long secondsIn(String line, String regex) {

        Matcher matcher = Pattern.compile(regex).matcher(line);
        assertThat(matcher.find()).as(line).isTrue();
        return Long.parseLong(matcher.group(1));

    }

    private static CollectedActivity activity(PartialReason partialReason) {

        CollectedPullRequest pr = new CollectedPullRequest(7, "feat: 워커 추가", null, GithubState.CLOSED, "grow22",
                "develop", "feature/job-worker", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T01:00:00Z"), List.of(SHA), List.of(), List.of());
        return new CollectedActivity(List.of(commit(SHA)), List.of(pr), List.of(), null, partialReason);

    }

    private static CollectedActivity newCommitActivity(String sha, PartialReason partialReason) {

        return new CollectedActivity(List.of(commit(sha)), List.of(), List.of(), null, partialReason);

    }

    private static CollectedActivity partialActivityOf(String sha) {

        return new CollectedActivity(List.of(commit(sha)), List.of(), List.of(), null, PartialReason.CAP_EXCEEDED);

    }

    private static CollectedCommit commit(String sha) {

        return new CollectedCommit(sha, "grow22", "Grow22", "feat: 워커 추가", Instant.parse("2026-10-01T00:00:00Z"),
                10, 2, 1, (short) 1, null, List.of(), List.of());

    }

    private int countOf(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }
}
