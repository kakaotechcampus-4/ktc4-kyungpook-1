package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.ExperienceCandidate;
import com.gitory.backend.agent.port.ExperienceGroupingPort;
import com.gitory.backend.agent.port.ExperienceGroupingRequest;
import com.gitory.backend.agent.port.ExperienceGroupingResponse;
import com.gitory.backend.agent.port.ExperienceSourceType;
import com.gitory.backend.agent.port.GroupingCommit;
import com.gitory.backend.agent.port.GroupingFile;
import com.gitory.backend.agent.port.GroupingFileStatus;
import com.gitory.backend.agent.port.GroupingIssue;
import com.gitory.backend.agent.port.GroupingPullRequest;
import com.gitory.backend.agent.port.GroupingVerdict;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ActivityStoreService;
import com.gitory.backend.ingest.domain.CollectionStatsService;
import com.gitory.backend.ingest.domain.FileChangeStatus;
import com.gitory.backend.ingest.domain.GithubState;
import com.gitory.backend.ingest.domain.GroupingMaterialService;
import com.gitory.backend.ingest.domain.PartialReason;
import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedFile;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateEmptyReasons;
import com.gitory.backend.recommend.domain.CandidateStoreService;
import com.gitory.backend.support.CandidateFixtures;
import com.gitory.backend.support.StoredActivityFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 묶기 스위치를 켠 분석 Job 이 수집 저장 → 묶기 재료 → AI 묶기 → 후보 저장 → 단계·판정까지 잇는지 본다
 * 갱신 시각 테스트는 16분 전 Job 이 멈춘 Job 기준(두 대기 중 큰 값 + 5분)을 넘어야 뜻이 있어, 두 대기를 기본값·환경변수와 상관없이 10분으로 고정한다
 */
@DataJpaTest(properties = {
        "gitory.ai.collect-read-timeout=10m",
        "gitory.ai.groups-read-timeout=10m",
        "gitory.analysis.grouping.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import({JobRunner.class, JobCancelService.class, JobQueryService.class, ActivityStoreService.class,
        GroupingMaterialService.class, CandidateStoreService.class, StoredActivityService.class, UserLoginService.class,
        JobViewFactory.class, CandidateEmptyReasons.class, CollectionStatsService.class,
        GeneratedCardQueryService.class})
@EnableConfigurationProperties(AiClientProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobRunnerGroupingTest {

    private static final String LOGIN = "Grow22";
    private static final String KEY_1 = "11111111-1111-4111-8111-111111111111";
    private static final String KEY_2 = "22222222-2222-4222-8222-222222222222";
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String SHA_OLD = "c".repeat(40);
    private static final Instant OCT_1 = Instant.parse("2026-10-01T00:00:00Z");

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
    JobQueryService jobQuery;

    @Autowired
    JdbcTemplate jdbc;

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

    @MockitoSpyBean
    CandidateStoreService candidateStore;

    private TestFixtures fixtures;
    private CandidateFixtures candidateRows;
    private StoredActivityFixtures storedRows;
    private ExecutorService otherThread;
    private Long userId;
    private Long repositoryId;
    private Long userRepositoryId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidateRows = new CandidateFixtures(jdbc);
        storedRows = new StoredActivityFixtures(jdbc);
        otherThread = Executors.newSingleThreadExecutor();
        // 사용자명을 커밋 작성자(grow22)와 대소문자만 다르게 두어, AI 에 보내는 사용자명이 users 표에서 오는지 가린다
        userId = fixtures.insertUser(1L, "Grow22");
        repositoryId = fixtures.insertRepository(100L, "grow22", "gitory");
        jdbc.update("UPDATE repository SET default_branch = 'develop' WHERE id = ?", repositoryId);
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);

    }

    @AfterEach
    void tearDown() {

        otherThread.shutdownNow();

    }

    @Test
    @DisplayName("묶기를 켜면 수집을 저장한 뒤 AI 가 묶은 후보를 저장하고, 묶은 기록을 남긴 채 SUCCEEDED 로 끝낸다")
    void storesGroupedCandidatesAndSucceeds() {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willAnswer(invocation -> groupedAsPullRequest(invocation.getArgument(0)));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.getErrorCode()).isNull();
        assertThat(candidateTitles()).containsExactly("워커 추가");
        assertThat(countOf("candidate_grouping")).isEqualTo(1);
        verify(grouping, times(1)).group(any());

    }

    @Test
    @DisplayName("AI 에는 저장소 연결 id, users 표의 사용자명, 저장된 내 커밋과 그 커밋이 가리키는 PR·이슈를 보낸다")
    void sendsStoredOwnCommitsWithUsersLogin() {

        enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willAnswer(invocation -> groupedAsPullRequest(invocation.getArgument(0)));

        runNext();

        verify(grouping).group(new ExperienceGroupingRequest(String.valueOf(userRepositoryId), "Grow22",
                List.of(new GroupingCommit(SHA_A, null, "feat: 워커 추가", "grow22", "Grow22", null,
                        OffsetDateTime.parse("2026-10-01T09:00:00+09:00"), 1, 10, 2, 1,
                        List.of(new GroupingFile("backend/Worker.java", GroupingFileStatus.MODIFIED, 10, 2, null)),
                        7, List.of(3), null, null)),
                List.of(new GroupingPullRequest(7, "feat: 워커 추가", "워커를 붙인다", List.of(3))),
                List.of(new GroupingIssue(3, "워커가 없다", "Job 이 실행되지 않는다")),
                false));

    }

    @Test
    @DisplayName("손댄 후보에 든 커밋과, 커밋의 PR 이 손댄 후보의 PR 인 커밋은 AI 에 보내지 않는다")
    void doesNotSendTouchedCandidateSources() {

        Long oldCommitId = storedRows.insertCommit(repositoryId, SHA_OLD, OCT_1.minusSeconds(3600), 1);
        Long excluded = candidateRows.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED");
        candidateRows.insertCandidateCommit(excluded, oldCommitId);
        Long used = candidateRows.insertCandidate(userRepositoryId, "PR", 9, "USED");
        jdbc.update("UPDATE candidate SET pull_request_numbers = '{9}' WHERE id = ?", used);
        enqueue(KEY_1);
        given(activities.collect(any())).willReturn(new CollectedActivity(List.of(commit(SHA_A), commit(SHA_B)),
                List.of(pullRequest(7, List.of(SHA_A)), pullRequest(9, List.of(SHA_B))), List.of(issue()), null, null));
        AtomicReference<ExperienceGroupingRequest> sent = new AtomicReference<>();
        given(grouping.group(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return groupedAsPullRequest(invocation.getArgument(0));
        });

        runNext();

        assertThat(sent.get().commits()).extracting(GroupingCommit::sha).containsExactly(SHA_A);
        assertThat(sent.get().pullRequests()).extracting(GroupingPullRequest::number).containsExactly(7);

    }

    @Test
    @DisplayName("AI 가 묶는 동안 수집 결과는 이미 저장돼 있고, Job 은 RUNNING 에 후보 추리기 진행 중·추천 이유 대기로 보인다")
    void showsGroupingStepsWhileWaitingForAi() {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        AtomicReference<Map<String, Object>> duringGrouping = new AtomicReference<>();
        // AI 를 부르는 스레드에서 읽으면 그 스레드가 연 트랜잭션 안의 값까지 보일 수 있어, 화면처럼 다른 스레드에서 커밋된 값만 읽는다
        given(grouping.group(any())).willAnswer(invocation -> {
            duringGrouping.set(otherThread.submit(() -> jdbc.queryForMap("""
                    SELECT state, steps -> 2 ->> 'state' AS compress, steps -> 3 ->> 'state' AS reason,
                           collection_run_id IS NOT NULL AS collected,
                           (SELECT count(*) FROM git_commit) AS commits
                    FROM analysis_job WHERE id = ?
                    """, jobId)).get(5, TimeUnit.SECONDS));
            return groupedAsPullRequest(invocation.getArgument(0));
        });

        runNext();

        assertThat(duringGrouping.get())
                .containsEntry("state", "RUNNING")
                .containsEntry("compress", "RUNNING")
                .containsEntry("reason", "QUEUED")
                .containsEntry("collected", true)
                .containsEntry("commits", 1L);

    }

    @Test
    @DisplayName("수집이 오래 걸려도 묶기 직전에 갱신 시각을 새로 찍어, 묶는 동안에는 멈춘 Job 으로 끝내지 않는다")
    void touchesUpdatedAtBeforeGrouping() {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willAnswer(invocation -> {
            jdbc.update("UPDATE analysis_job SET updated_at = now() - interval '16 minutes' WHERE id = ?", jobId);
            return activity(null, commit(SHA_A));
        });
        AtomicReference<JobState> stateDuringGrouping = new AtomicReference<>();
        given(grouping.group(any())).willAnswer(invocation -> {
            otherThread.submit(runner::failStuckJobs).get(5, TimeUnit.SECONDS);
            stateDuringGrouping.set(otherThread.submit(() -> stateOf(jobId)).get(5, TimeUnit.SECONDS));
            return groupedAsPullRequest(invocation.getArgument(0));
        });

        runNext();

        assertThat(stateDuringGrouping.get()).isEqualTo(JobState.RUNNING);
        assertThat(stateOf(jobId)).isEqualTo(JobState.SUCCEEDED);

    }

    @Test
    @DisplayName("묶은 뒤 후보 추리기·추천 이유는 상태와 상관없이 그 저장소 연결의 후보 수로 완료되고 판정은 OK 다")
    void finishesCandidateStepsWithConnectionCandidateCount() {

        candidateRows.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED");
        Long teammateId = fixtures.insertUser(2L, "teammate");
        Long teammateRepositoryId = fixtures.insertUserRepository(teammateId, repositoryId);
        candidateRows.insertCandidate(teammateRepositoryId, "COMMIT_CLUSTER", null, "NEW");
        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willAnswer(invocation -> groupedAsPullRequest(invocation.getArgument(0)));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 1, null),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.DONE, 1, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.DONE, 2, null),
                new JobStep(JobStepKey.REASON, JobStepState.DONE, 2, null));
        assertThat(resultOf(job)).isEqualTo(new JobResult(userRepositoryPublicId(), null, "OK", List.of()));

    }

    @Test
    @DisplayName("AI 가 후보를 하나도 만들지 않으면 후보 추리기는 0 으로 완료되고 추천 이유는 SKIPPED 이며 판정은 EMPTY 이고 이유 줄은 저장된 숫자로 만든다")
    void emptyGroupingSkipsReasonAndJudgesEmpty() {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willReturn(
                new ExperienceGroupingResponse(GroupingVerdict.EMPTY, List.of(), List.of(SHA_A)));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.getSteps()).endsWith(
                new JobStep(JobStepKey.COMPRESS, JobStepState.DONE, 0, null),
                new JobStep(JobStepKey.REASON, JobStepState.SKIPPED, 0, null));
        assertThat(resultOf(job)).isEqualTo(new JobResult(userRepositoryPublicId(), null, "EMPTY",
                List.of("내 커밋 수를 아직 세지 못했어요", "커밋 메시지 평균 11글자")));

    }

    @Test
    @DisplayName("부분 수집이면 AI 에 부분 수집으로 알리고, 묶은 뒤에도 부분 완료와 GitHub 한도 에러 코드를 남기며 판정은 PARTIAL 이다")
    void keepsPartialCollectionThroughGrouping() {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED, commit(SHA_A)));
        AtomicReference<ExperienceGroupingRequest> sent = new AtomicReference<>();
        given(grouping.group(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return groupedAsPullRequest(invocation.getArgument(0));
        });

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(sent.get().collectionPartial()).isTrue();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);
        assertThat(resultOf(job)).isEqualTo(new JobResult(userRepositoryPublicId(), null, "PARTIAL", List.of()));

    }

    @Test
    @DisplayName("경험 그룹화를 마친 한도 부분 결과의 남은 초는 그룹화가 끝난 시각부터 풀리는 시각까지로 센다")
    void countsRetryAfterSecFromEndOfGrouping() throws Exception {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED, commit(SHA_A)));
        given(githubAccess.rateLimitResetAt(userId)).willReturn(Optional.of(Instant.now().plusSeconds(3600)));
        given(grouping.group(any())).willAnswer(invocation -> {
            Thread.sleep(1100);
            return groupedAsPullRequest(invocation.getArgument(0));
        });

        runNext();

        assertThat(jobs.findById(jobId).orElseThrow().getRetryAfterSec()).isBetween(3590, 3599);

    }

    @Test
    @DisplayName("지난 후보를 그대로 두고 끝내는 한도 부분 결과도 끝난 시각부터 남은 초를 센다")
    void countsRetryAfterSecWhenKeepingCandidates() {

        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED, commit(SHA_A)));
        given(grouping.group(any())).willAnswer(invocation -> groupedAsPullRequest(invocation.getArgument(0)));
        given(githubAccess.rateLimitResetAt(userId)).willReturn(Optional.empty());
        enqueue(KEY_1);
        runNext();
        given(githubAccess.rateLimitResetAt(userId)).willReturn(Optional.of(Instant.now().plusSeconds(600)));

        Long secondJobId = enqueue(KEY_2);
        runNext();

        verify(grouping, times(1)).group(any());
        assertThat(jobs.findById(secondJobId).orElseThrow().getRetryAfterSec()).isBetween(590, 600);

    }

    @Test
    @DisplayName("경험 그룹화 사이 한도가 풀리는 시각이 지났으면 남은 초는 비어 있고 부분 완료·GITHUB_RATE_LIMITED 는 그대로다")
    void leavesRetryAfterSecEmptyWhenResetPassedDuringGrouping() throws Exception {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(PartialReason.GITHUB_RATE_LIMITED, commit(SHA_A)));
        given(githubAccess.rateLimitResetAt(userId)).willReturn(Optional.of(Instant.now().plusMillis(500)));
        given(grouping.group(any())).willAnswer(invocation -> {
            Thread.sleep(1000);
            return groupedAsPullRequest(invocation.getArgument(0));
        });

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.isPartial()).isTrue();
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.GITHUB_RATE_LIMITED);
        assertThat(job.getRetryAfterSec()).isNull();

    }

    @Test
    @DisplayName("보낼 요청이 지난번 묶었을 때와 같으면 AI 를 부르지 않고 지난 후보를 그대로 두며, 단계를 완료하고 묶은 시각을 새로 찍는다")
    void skipsAiWhenInputIsSame() {

        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willAnswer(invocation -> groupedAsPullRequest(invocation.getArgument(0)));
        enqueue(KEY_1);
        runNext();
        List<Long> firstCandidates = candidateIds();
        Instant longAgo = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("UPDATE candidate_grouping SET grouped_at = ?", Timestamp.from(longAgo));

        Long secondJobId = enqueue(KEY_2);
        runNext();

        AnalysisJob second = jobs.findById(secondJobId).orElseThrow();
        verify(grouping, times(1)).group(any());
        assertThat(candidateIds()).isEqualTo(firstCandidates);
        assertThat(jdbc.queryForObject("SELECT grouped_at FROM candidate_grouping", Timestamp.class).toInstant())
                .isAfter(longAgo);
        assertThat(second.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(second.getSteps()).endsWith(
                new JobStep(JobStepKey.COMPRESS, JobStepState.DONE, 1, null),
                new JobStep(JobStepKey.REASON, JobStepState.DONE, 1, null));

    }

    @Test
    @DisplayName("보낼 요청이 지난번과 다르면 AI 를 다시 불러 손대지 않은 후보를 새로 묶은 후보로 바꾼다")
    void regroupsWhenInputChanges() {

        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        List<ExperienceGroupingRequest> sent = new ArrayList<>();
        given(grouping.group(any())).willAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return groupedAsPullRequest(invocation.getArgument(0));
        });
        enqueue(KEY_1);
        runNext();
        List<Long> firstCandidates = candidateIds();

        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A), commit(SHA_B)));
        enqueue(KEY_2);
        runNext();

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).commits()).extracting(GroupingCommit::sha).containsExactly(SHA_A, SHA_B);
        assertThat(candidateIds()).hasSize(1).doesNotContainAnyElementsOf(firstCandidates);

    }

    @Test
    @DisplayName("AI 묶기가 실패하면 수집 결과와 전에 만든 후보는 남기고, 다시 부르지 않은 채 다시 시도할 수 없는 INTERNAL_ERROR 로 끝낸다")
    void groupingFailureKeepsCollectionAndOldCandidates() {

        Long oldCandidate = candidateRows.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW");
        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willThrow(
                new AgentUnavailableException("AI 후보 묶기 실패 (LLM_UNAVAILABLE)", true, false, null));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        verify(grouping, times(1)).group(any());
        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.INTERNAL_ERROR);
        assertThat(jobQuery.load(job.getPublicId(), userId, LOGIN).retryable()).isFalse();
        assertThat(job.getCollectionRunId()).isNotNull();
        assertThat(countOf("collection_run")).isEqualTo(1);
        assertThat(countOf("git_commit")).isEqualTo(1);
        assertThat(candidateIds()).containsExactly(oldCandidate);
        assertThat(countOf("candidate_grouping")).isZero();

    }

    @Test
    @DisplayName("묶은 후보를 저장하다 실패하면 후보를 하나도 바꾸지 않고 수집 결과는 남긴 채 INTERNAL_ERROR 로 끝낸다")
    void storeFailureKeepsOldCandidates() {

        Long oldCandidate = candidateRows.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW");
        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willReturn(new ExperienceGroupingResponse(GroupingVerdict.OK, List.of(
                pullRequestCandidate(List.of("f".repeat(40)))), List.of()));

        runNext();

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(JobErrorCode.INTERNAL_ERROR);
        assertThat(job.getCollectionRunId()).isNotNull();
        assertThat(candidateIds()).containsExactly(oldCandidate);
        assertThat(countOf("candidate_grouping")).isZero();

    }

    @Test
    @DisplayName("AI 가 묶는 동안 취소되면 묶은 후보를 저장하지 않고 CANCELED 로 두며, 실패로 끝냈다고 기록하지 않는다")
    void discardsCandidatesWhenCanceledDuringGrouping(CapturedOutput output) {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willAnswer(invocation -> {
            cancel(jobId);
            return groupedAsPullRequest(invocation.getArgument(0));
        });

        runNext();

        assertThat(stateOf(jobId)).isEqualTo(JobState.CANCELED);
        verify(candidateStore, never()).store(any(), any(), any());
        assertThat(countOf("candidate")).isZero();
        assertThat(countOf("candidate_grouping")).isZero();
        assertThat(output).doesNotContain("INTERNAL_ERROR 로 끝낸다");

    }

    @Test
    @DisplayName("입력이 같아 AI 를 건너뛰는 사이 취소되면 CANCELED 로 두고, 실패로 끝냈다고 기록하지 않는다")
    void keepsCanceledWhenCanceledWhileSkipping(CapturedOutput output) {

        given(activities.collect(any())).willReturn(activity(null, commit(SHA_A)));
        given(grouping.group(any())).willAnswer(invocation -> groupedAsPullRequest(invocation.getArgument(0)));
        enqueue(KEY_1);
        runNext();
        Long secondJobId = enqueue(KEY_2);
        willAnswer(invocation -> {
            cancel(secondJobId);
            return invocation.callRealMethod();
        }).given(candidateStore).keepIfSameInput(any(), any());

        runNext();

        assertThat(stateOf(secondJobId)).isEqualTo(JobState.CANCELED);
        verify(grouping, times(1)).group(any());
        assertThat(output).doesNotContain("INTERNAL_ERROR 로 끝낸다");

    }

    @Test
    @DisplayName("수집을 기다리는 사이 취소되면 AI 묶기를 부르지 않는다")
    void doesNotGroupJobCanceledDuringCollection() {

        Long jobId = enqueue(KEY_1);
        given(activities.collect(any())).willAnswer(invocation -> {
            cancel(jobId);
            return activity(null, commit(SHA_A));
        });

        runNext();

        assertThat(stateOf(jobId)).isEqualTo(JobState.CANCELED);
        verifyNoInteractions(grouping);

    }

    private boolean runNext() {

        Optional<AnalysisJob> claimed = runner.claimNext();
        claimed.ifPresent(runner::run);
        return claimed.isPresent();

    }

    private Long enqueue(String key) {

        return jobs.save(AnalysisJob.enqueue(userId, userRepositoryId, key)).getId();

    }

    private void cancel(Long jobId) {

        AnalysisJob job = jobs.findById(jobId).orElseThrow();
        cancelService.cancel(job.getPublicId(), job.getUserId(), LOGIN);

    }

    private JobState stateOf(Long jobId) {

        return jobs.findById(jobId).orElseThrow().getState();

    }

    private JobResult resultOf(AnalysisJob job) {

        return jobQuery.load(job.getPublicId(), userId, LOGIN).result();

    }

    private String userRepositoryPublicId() {

        return jdbc.queryForObject("SELECT public_id::text FROM user_repository WHERE id = ?", String.class,
                userRepositoryId);

    }

    private List<Long> candidateIds() {

        return jdbc.queryForList("SELECT id FROM candidate WHERE user_repository_id = ? ORDER BY id", Long.class,
                userRepositoryId);

    }

    private List<String> candidateTitles() {

        return jdbc.queryForList("SELECT title FROM candidate WHERE user_repository_id = ? ORDER BY id", String.class,
                userRepositoryId);

    }

    private int countOf(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }

    /** 받은 커밋을 모두 PR 7 후보 하나로 묶은 AI 응답 */
    private static ExperienceGroupingResponse groupedAsPullRequest(ExperienceGroupingRequest request) {

        return new ExperienceGroupingResponse(GroupingVerdict.OK,
                List.of(pullRequestCandidate(request.commits().stream().map(GroupingCommit::sha).toList())),
                List.of());

    }

    private static ExperienceCandidate pullRequestCandidate(List<String> commitShas) {

        return new ExperienceCandidate("pr:7", ExperienceSourceType.PR, "#7", 7, List.of(7), List.of(3), "워커 추가",
                "Job 을 실행하는 워커를 만든 작업", 0.8, false, commitShas);

    }

    private static CollectedActivity activity(PartialReason partialReason, CollectedCommit... commits) {

        List<CollectedCommit> collected = List.of(commits);
        return new CollectedActivity(collected,
                List.of(pullRequest(7, collected.stream().map(CollectedCommit::sha).toList())), List.of(issue()), null,
                partialReason);

    }

    private static CollectedCommit commit(String sha) {

        return new CollectedCommit(sha, "grow22", "Grow22", "feat: 워커 추가", OCT_1, 10, 2, 1, (short) 1, null,
                List.of(new CollectedFile("backend/Worker.java", FileChangeStatus.MODIFIED, 10, 2)), List.of(3));

    }

    private static CollectedPullRequest pullRequest(int number, List<String> commitShas) {

        return new CollectedPullRequest(number, "feat: 워커 추가", "워커를 붙인다", GithubState.CLOSED, "grow22", "develop",
                "feature/job-worker-" + number, OCT_1, OCT_1.plusSeconds(3600), commitShas, List.of(3), List.of());

    }

    private static CollectedIssue issue() {

        return new CollectedIssue(3, "워커가 없다", "Job 이 실행되지 않는다", GithubState.CLOSED, "grow22", List.of(), OCT_1,
                OCT_1.plusSeconds(7200));

    }
}
