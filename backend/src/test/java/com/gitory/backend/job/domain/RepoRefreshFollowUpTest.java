package com.gitory.backend.job.domain;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CandidateCardService;
import com.gitory.backend.card.domain.CardVersionWriter;
import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.recommend.domain.CandidateStoreService;
import com.gitory.backend.recommend.domain.CandidateUseService;
import com.gitory.backend.recommend.domain.RefreshCandidatePicker;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.StoredActivityFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = "gitory.cards.ai-draft.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ClockConfig.class, RepoRefreshFollowUp.class, RepoRefreshRecorder.class, RefreshCandidatePicker.class,
        CommitSummaryService.class, CardCreationService.class, CandidateUseService.class, CandidateCardService.class,
        CardVersionWriter.class, CandidateStoreService.class, StoredActivityService.class,
        RepositoryOwnershipService.class, AuditLog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepoRefreshFollowUpTest {

    private static final Instant PREVIOUS = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AFTER_PREVIOUS = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant BEFORE_PREVIOUS = Instant.parse("2026-10-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    UserRateLimiter rateLimiter;

    private CandidateCardFixtures candidates;
    private StoredActivityFixtures activity;
    private Long userId;
    private Long repositoryId;
    private Long userRepositoryId;
    private Long collectionRunId;
    private int shaSeq;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidates = new CandidateCardFixtures(jdbc);
        activity = new StoredActivityFixtures(jdbc);
        userId = fixtures.insertUser(1L, "grow22");
        repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        collectionRunId = jdbc.queryForObject(
                "INSERT INTO collection_run (user_repository_id) VALUES (?) RETURNING id", Long.class, userRepositoryId);
        shaSeq = 0;

    }

    @Test
    @DisplayName("분석이 다 읽고 끝나면 새 일이 든 후보로만 카드와 초안 Job 을 만들고 기록을 초안 Job 과 카드 수를 담은 DRAFTING 으로 바꾼다")
    void startsDraftingForCandidatesWithNewWork() {

        Long withNewWork = candidate("새 일이 든 후보", AFTER_PREVIOUS);
        Long withoutNewWork = candidate("옛 일뿐인 후보", BEFORE_PREVIOUS);
        Long analysisJobId = analysisJob("SUCCEEDED", false);
        reading(analysisJobId);

        events.publishEvent(new AnalysisFinished(analysisJobId));

        Map<String, Object> refresh = jdbc.queryForMap("SELECT * FROM repo_refresh");
        assertThat(refresh.get("state")).isEqualTo("DRAFTING");
        assertThat(((Number) refresh.get("card_count")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT type, state FROM analysis_job WHERE id = ?", refresh.get("draft_job_id")))
                .containsEntry("type", "DRAFT").containsEntry("state", "QUEUED");
        assertThat(jdbc.queryForList("SELECT candidate_id FROM card", Long.class)).containsExactly(withNewWork);
        assertThat(candidates.statusOf(withNewWork)).isEqualTo("USED");
        assertThat(candidates.statusOf(withoutNewWork)).isEqualTo("NEW");

    }

    @Test
    @DisplayName("새 일이 든 후보가 없으면 카드를 만들지 않고 기록을 알리지 않는 CLOSED 로 닫는다")
    void closesWhenNoCandidateHasNewWork() {

        candidate("옛 일뿐인 후보", BEFORE_PREVIOUS);
        Long analysisJobId = analysisJob("SUCCEEDED", false);
        reading(analysisJobId);

        events.publishEvent(new AnalysisFinished(analysisJobId));

        assertClosedWithoutCards();

    }

    @ParameterizedTest
    @CsvSource({"FAILED,false", "CANCELED,false", "SUCCEEDED,true"})
    @DisplayName("분석이 실패·취소되거나 일부만 읽고 끝나면 새 일이 든 후보가 있어도 카드를 만들지 않고 기록을 CLOSED 로 닫는다")
    void closesWhenAnalysisDidNotReadEverything(String state, boolean partial) {

        candidate("새 일이 든 후보", AFTER_PREVIOUS);
        Long analysisJobId = analysisJob(state, partial);
        reading(analysisJobId);

        events.publishEvent(new AnalysisFinished(analysisJobId));

        assertClosedWithoutCards();

    }

    @Test
    @DisplayName("카드 만들기가 호출 제한 같은 예외로 거절되면 아무것도 만들지 않고 기록을 CLOSED 로 닫으며, 예외는 새지 않고 분석 Job 의 결과도 그대로다")
    void closesWithoutBreakingTheAnalysisWhenCardCreationIsRejected() {

        Long newWork = candidate("새 일이 든 후보", AFTER_PREVIOUS);
        Long analysisJobId = analysisJob("SUCCEEDED", false);
        reading(analysisJobId);
        doThrow(new RateLimitExceededException(60)).when(rateLimiter).acquire(any(), any(), anyInt());

        assertThatCode(() -> events.publishEvent(new AnalysisFinished(analysisJobId))).doesNotThrowAnyException();

        assertClosedWithoutCards();
        assertThat(candidates.statusOf(newWork)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT state FROM analysis_job WHERE id = ?", String.class, analysisJobId))
                .isEqualTo("SUCCEEDED");

    }

    @Test
    @DisplayName("카드 초안 Job 이 SUCCEEDED 로 끝나면 기록이 READY 가 되고 만든 카드 수가 남는다")
    void becomesReadyWhenDraftJobSucceeds() {

        Long analysisJobId = analysisJob("SUCCEEDED", false);
        Long draftJobId = drafting(analysisJobId, 3);

        endAfterCommit(draftJobId, "SUCCEEDED", new JobEnded(draftJobId));

        Map<String, Object> refresh = jdbc.queryForMap("SELECT * FROM repo_refresh");
        assertThat(refresh.get("state")).isEqualTo("READY");
        assertThat(((Number) refresh.get("card_count")).intValue()).isEqualTo(3);
        assertThat(refresh.get("finished_at")).isNotNull();
        assertThat(refresh.get("seen_at")).isNull();

    }

    @ParameterizedTest
    @CsvSource({"FAILED", "CANCELED"})
    @DisplayName("카드 초안 Job 이 실패하거나 취소되면 기록이 알리지 않는 CLOSED 가 된다")
    void closesWhenDraftJobDoesNotSucceed(String state) {

        Long analysisJobId = analysisJob("SUCCEEDED", false);
        Long draftJobId = drafting(analysisJobId, 3);

        endAfterCommit(draftJobId, state, state.equals("FAILED")
                ? new JobFailed(draftJobId, JobType.DRAFT, JobErrorCode.INTERNAL_ERROR) : new JobEnded(draftJobId));

        assertThat(jdbc.queryForObject("SELECT state FROM repo_refresh", String.class)).isEqualTo("CLOSED");

    }

    @Test
    @DisplayName("스스로 다시 읽은 분석 Job 이 끝나기 전에 실패하거나 취소되면 기록이 CLOSED 가 되고 이미 초안으로 넘어간 기록은 그대로다")
    void closesReadingWhenAnalysisJobFailsButNotDrafting() {

        Long failedJobId = analysisJob("FAILED", false);
        reading(failedJobId);
        Long draftingJobId = analysisJob("SUCCEEDED", false);
        drafting(draftingJobId, 2);

        endAfterCommit(failedJobId, "FAILED", new JobFailed(failedJobId, JobType.ANALYZE, JobErrorCode.INTERNAL_ERROR));
        endAfterCommit(draftingJobId, "CANCELED", new JobEnded(draftingJobId));

        assertThat(jdbc.queryForObject("SELECT state FROM repo_refresh WHERE analysis_job_id = ?", String.class,
                failedJobId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT state FROM repo_refresh WHERE analysis_job_id = ?", String.class,
                draftingJobId)).isEqualTo("DRAFTING");

    }

    @Test
    @DisplayName("기록이 없는 분석 Job 과 초안 Job 은 끝나도 카드도 기록도 만들지 않는다")
    void leavesJobsWithoutRecordAlone() {

        candidate("새 일이 든 후보", AFTER_PREVIOUS);
        Long analysisJobId = analysisJob("SUCCEEDED", false);
        Long draftJobId = jdbc.queryForObject(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, 'DRAFT', 'SUCCEEDED') RETURNING id",
                Long.class, userId, userRepositoryId, UUID.randomUUID().toString());

        events.publishEvent(new AnalysisFinished(analysisJobId));
        endAfterCommit(draftJobId, "SUCCEEDED", new JobEnded(draftJobId));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM repo_refresh", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job", Integer.class)).isEqualTo(2);

    }

    private void assertClosedWithoutCards() {

        Map<String, Object> refresh = jdbc.queryForMap("SELECT state, finished_at, draft_job_id FROM repo_refresh");
        assertThat(refresh.get("state")).isEqualTo("CLOSED");
        assertThat(refresh.get("finished_at")).isNotNull();
        assertThat(refresh.get("draft_job_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job WHERE type = 'DRAFT'", Integer.class)).isZero();

    }

    // 커밋 뒤에 받는 이벤트라 Job 상태를 바꾼 트랜잭션 안에서 이벤트를 내보낸다
    private void endAfterCommit(Long jobId, String state, Object event) {

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE analysis_job SET state = ?, finished_at = now() WHERE id = ?", state, jobId);
            events.publishEvent(event);
        });

    }

    private Long candidate(String title, Instant authoredAt) {

        Long candidateId = candidates.insertNewCandidate(userRepositoryId, title);
        Long commitId = activity.insertCommit(repositoryId, String.format("%040x", ++shaSeq), authoredAt, 1);
        jdbc.update("UPDATE git_commit SET collection_run_id = ? WHERE id = ?",
                authoredAt.isAfter(PREVIOUS) ? collectionRunId : null, commitId);
        candidates.insertCandidateCommit(candidateId, commitId, true);

        return candidateId;

    }

    private Long analysisJob(String state, boolean partial) {

        return jdbc.queryForObject("""
                        INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state, partial,
                            collection_run_id, finished_at)
                        VALUES (?, ?, ?, 'ANALYZE', ?, ?, ?, now()) RETURNING id
                        """,
                Long.class, userId, userRepositoryId, UUID.randomUUID().toString(), state, partial, collectionRunId);

    }

    private void reading(Long analysisJobId) {

        jdbc.update("""
                INSERT INTO repo_refresh (user_id, user_repository_id, analysis_job_id, previous_analyzed_at, state, created_at)
                VALUES (?, ?, ?, ?, 'READING', now())
                """, userId, userRepositoryId, analysisJobId, Timestamp.from(PREVIOUS));

    }

    private Long drafting(Long analysisJobId, int cardCount) {

        Long draftJobId = jdbc.queryForObject(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, 'DRAFT', 'RUNNING') RETURNING id",
                Long.class, userId, userRepositoryId, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO repo_refresh (user_id, user_repository_id, analysis_job_id, draft_job_id, previous_analyzed_at,
                    card_count, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFTING', now())
                """, userId, userRepositoryId, analysisJobId, draftJobId, Timestamp.from(PREVIOUS), cardCount);

        return draftJobId;

    }
}
