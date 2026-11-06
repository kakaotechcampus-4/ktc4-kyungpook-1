package com.gitory.backend.job.domain;

import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.notice.domain.NoticeService;
import com.gitory.backend.recommend.domain.RefreshCandidatePicker;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ClockConfig.class, JobNoticeListener.class, JobNoticeRecorder.class, NoticeService.class,
        RepositoryReferenceService.class, GeneratedCardQueryService.class, RepoRefreshFollowUp.class,
        RepoRefreshRecorder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobNoticeListenerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobNoticeListener listener;

    @MockitoSpyBean
    NoticeService notices;

    @MockitoBean
    RefreshCandidatePicker picker;

    @MockitoBean
    CardCreationService cardCreation;

    private Long userId;
    private Long userRepositoryId;
    private UUID repoPublicId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        repoPublicId = jdbc.queryForObject("SELECT public_id FROM user_repository WHERE id = ?", UUID.class,
                userRepositoryId);

    }

    @Test
    @DisplayName("카드 초안 Job 이 SUCCEEDED 로 끝나면 레포·카드 수·카드 제목 3개까지·Job 을 담은 CARDS_DRAFTED 알림 한 줄을 남긴다")
    void leavesCardsDraftedNoticeWhenDraftSucceeds() {

        Map<String, Object> draft = job("DRAFT", "RUNNING");
        UUID jobPublicId = (UUID) draft.get("public_id");
        for (String title : List.of("첫째", "둘째", "셋째", "넷째")) {
            card(jobPublicId, title);
        }

        end((Long) draft.get("id"), "SUCCEEDED", new JobEnded((Long) draft.get("id")));

        Map<String, Object> notice = jdbc.queryForMap("SELECT user_id, kind, payload::text AS payload FROM notification");
        assertThat(notice.get("user_id")).isEqualTo(userId);
        assertThat(notice.get("kind")).isEqualTo("CARDS_DRAFTED");
        assertThat(payloadOf("repoId")).isEqualTo(repoPublicId.toString());
        assertThat(payloadOf("repoName")).isEqualTo("grow22/gitory");
        assertThat(payloadOf("cardCount")).isEqualTo("4");
        assertThat(payloadOf("jobId")).isEqualTo(jobPublicId.toString());
        assertThat(jdbc.queryForObject("SELECT payload->'cardTitles' FROM notification", String.class))
                .isEqualTo("[\"첫째\", \"둘째\", \"셋째\"]");

    }

    @Test
    @DisplayName("스스로 다시 읽기 기록의 초안 Job 이 끝나면 내용은 같고 종류가 REPO_REFRESHED 다")
    void leavesRepoRefreshedNoticeForSelfRefreshDraft() {

        Map<String, Object> analysis = job("ANALYZE", "SUCCEEDED");
        Map<String, Object> draft = job("DRAFT", "RUNNING");
        UUID jobPublicId = (UUID) draft.get("public_id");
        card(jobPublicId, "다시 읽어 만든 카드");
        refresh((Long) analysis.get("id"), (Long) draft.get("id"));

        end((Long) draft.get("id"), "SUCCEEDED", new JobEnded((Long) draft.get("id")));

        assertThat(jdbc.queryForObject("SELECT kind FROM notification", String.class)).isEqualTo("REPO_REFRESHED");
        assertThat(payloadOf("repoName")).isEqualTo("grow22/gitory");
        assertThat(payloadOf("cardCount")).isEqualTo("1");
        assertThat(payloadOf("jobId")).isEqualTo(jobPublicId.toString());

    }

    @Test
    @DisplayName("레포 분석 Job 이 FAILED 로 끝나면 레포·Job·errorCode 를 담은 ANALYSIS_FAILED 알림을 남긴다")
    void leavesAnalysisFailedNoticeWhenAnalysisFails() {

        Map<String, Object> analysis = job("ANALYZE", "RUNNING");
        Long jobId = (Long) analysis.get("id");

        end(jobId, "FAILED", new JobFailed(jobId, JobType.ANALYZE, JobErrorCode.GITHUB_UNAUTHORIZED));

        assertThat(jdbc.queryForObject("SELECT kind FROM notification", String.class)).isEqualTo("ANALYSIS_FAILED");
        assertThat(payloadOf("repoId")).isEqualTo(repoPublicId.toString());
        assertThat(payloadOf("repoName")).isEqualTo("grow22/gitory");
        assertThat(payloadOf("jobId")).isEqualTo(analysis.get("public_id").toString());
        assertThat(payloadOf("errorCode")).isEqualTo("GITHUB_UNAUTHORIZED");
        assertThat(payloadOf("cardCount")).isNull();

    }

    @Test
    @DisplayName("스스로 다시 읽기가 접수한 분석 Job 이 실패하면 알림을 남기지 않는다")
    void leavesNothingWhenSelfRefreshAnalysisFails() {

        Map<String, Object> analysis = job("ANALYZE", "RUNNING");
        Long jobId = (Long) analysis.get("id");
        refresh(jobId, null);

        end(jobId, "FAILED", new JobFailed(jobId, JobType.ANALYZE, JobErrorCode.GITHUB_UNAUTHORIZED));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isZero();

    }

    @ParameterizedTest
    @CsvSource({"DRAFT,CANCELED", "DRAFT,FAILED", "ANALYZE,SUCCEEDED"})
    @DisplayName("취소된 Job, 실패한 초안 Job, 성공한 분석 Job 은 알림을 남기지 않는다")
    void leavesNothingForOtherEndings(String type, String state) {

        Map<String, Object> job = job(type, "RUNNING");
        Long jobId = (Long) job.get("id");
        card((UUID) job.get("public_id"), "카드");
        Object event = state.equals("FAILED")
                ? new JobFailed(jobId, JobType.valueOf(type), JobErrorCode.INTERNAL_ERROR) : new JobEnded(jobId);

        end(jobId, state, event);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isZero();

    }

    @Test
    @DisplayName("알림을 남기다 예외가 나도 Job 의 결과는 그대로이고 같은 이벤트를 받는 스스로 다시 읽기 기록도 READY 로 바뀐다")
    void failureToLeaveNoticeDoesNotAffectJobOrRepoRefresh() {

        Map<String, Object> analysis = job("ANALYZE", "SUCCEEDED");
        Map<String, Object> draft = job("DRAFT", "RUNNING");
        Long draftJobId = (Long) draft.get("id");
        card((UUID) draft.get("public_id"), "카드");
        drafting((Long) analysis.get("id"), draftJobId);
        doThrow(new IllegalStateException("알림 저장 실패")).when(notices).leave(any(), any(), any());

        assertThatCode(() -> end(draftJobId, "SUCCEEDED", new JobEnded(draftJobId))).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT state FROM analysis_job WHERE id = ?", String.class, draftJobId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT state FROM repo_refresh", String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isZero();

    }

    @Test
    @DisplayName("알림을 남기다 예외가 나도 리스너는 예외를 밖으로 던지지 않는다")
    void listenerSwallowsFailureToLeaveNotice() {

        Map<String, Object> draft = job("DRAFT", "SUCCEEDED");
        Map<String, Object> analysis = job("ANALYZE", "FAILED");
        jdbc.update("UPDATE analysis_job SET error_code = 'GITHUB_UNAUTHORIZED' WHERE id = ?", analysis.get("id"));
        card((UUID) draft.get("public_id"), "카드");
        doThrow(new IllegalStateException("알림 저장 실패")).when(notices).leave(any(), any(), any());

        assertThatCode(() -> listener.jobEnded(new JobEnded((Long) draft.get("id")))).doesNotThrowAnyException();
        assertThatCode(() -> listener.jobFailed(new JobFailed((Long) analysis.get("id"), JobType.ANALYZE,
                JobErrorCode.GITHUB_UNAUTHORIZED))).doesNotThrowAnyException();

    }

    // 커밋 뒤에 받는 이벤트라 Job 상태를 바꾼 트랜잭션 안에서 이벤트를 내보낸다
    private void end(Long jobId, String state, Object event) {

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE analysis_job SET state = ?, finished_at = now(), error_code = ? WHERE id = ?", state,
                    state.equals("FAILED") ? "GITHUB_UNAUTHORIZED" : null, jobId);
            events.publishEvent(event);
        });

    }

    private String payloadOf(String key) {

        return jdbc.queryForObject("SELECT payload->>'" + key + "' FROM notification", String.class);

    }

    private Map<String, Object> job(String type, String state) {

        return jdbc.queryForMap("""
                INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state)
                VALUES (?, ?, ?, ?, ?) RETURNING id, public_id
                """, userId, userRepositoryId, UUID.randomUUID().toString(), type, state);

    }

    private void card(UUID generationJobId, String title) {

        jdbc.update("""
                INSERT INTO card (public_id, user_id, user_repository_id, card_type, origin, title, generation_job_id)
                VALUES (gen_random_uuid(), ?, ?, 'TECH', 'AI', ?, ?)
                """, userId, userRepositoryId, title, generationJobId);

    }

    private void refresh(Long analysisJobId, Long draftJobId) {

        jdbc.update("""
                INSERT INTO repo_refresh (user_id, user_repository_id, analysis_job_id, draft_job_id, previous_analyzed_at,
                    card_count, state, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, 1, 'CLOSED', now(), now())
                """, userId, userRepositoryId, analysisJobId, draftJobId, Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));

    }

    private void drafting(Long analysisJobId, Long draftJobId) {

        jdbc.update("""
                INSERT INTO repo_refresh (user_id, user_repository_id, analysis_job_id, draft_job_id, previous_analyzed_at,
                    card_count, state, created_at)
                VALUES (?, ?, ?, ?, ?, 1, 'DRAFTING', now())
                """, userId, userRepositoryId, analysisJobId, draftJobId, Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));

    }
}
