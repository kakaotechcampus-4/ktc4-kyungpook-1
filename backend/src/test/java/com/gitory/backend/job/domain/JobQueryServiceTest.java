package com.gitory.backend.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.ingest.domain.CollectionStatsService;
import com.gitory.backend.recommend.domain.CandidateEmptyReasons;
import com.gitory.backend.support.CandidateCardFixtures;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
@Import({ClockConfig.class, JobQueryService.class, JobViewFactory.class, JobActivityLog.class, CandidateEmptyReasons.class, CollectionStatsService.class,
        GeneratedCardQueryService.class})
class JobQueryServiceTest {

    private static final String LOGIN = "grow22";
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

    @MockitoBean
    AuditLog auditLog;

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

        UUID userRepositoryPublicId = jdbc.queryForObject(
                "SELECT public_id FROM user_repository WHERE id = ?", UUID.class, myRepoId);

        JobView found = service.load(job.getPublicId(), myUserId, LOGIN);

        assertThat(found.jobId()).isEqualTo(job.getPublicId().toString());
        assertThat(found.type()).isEqualTo(JobType.ANALYZE);
        assertThat(found.state()).isEqualTo(JobState.QUEUED);
        assertThat(found.partial()).isFalse();
        assertThat(found.steps())
                .extracting(JobStep::key)
                .containsExactly(JobStepKey.PR_REVIEW, JobStepKey.COMMITS, JobStepKey.COMPRESS, JobStepKey.REASON);
        assertThat(found.errorCode()).isNull();
        assertThat(found.retryable()).isNull();
        assertThat(found.retryAfterSec()).isNull();
        assertThat(found.result()).isEqualTo(new JobResult(userRepositoryPublicId.toString(), null, null, null));
        assertThat(found.startedAt()).isNotNull();
        assertThat(found.updatedAt()).isNotNull();
        assertThat(found.finishedAt()).isNull();

    }

    @Test
    @DisplayName("초안(DRAFT) Job 의 result 는 저장소 id 와 Job 이 만든 카드 id 를 카드 만든 순서대로 담고, 대기 중일 때도 끝난 뒤에도 같으며 판정과 이유는 비운다")
    void draftJobResultCarriesRepositoryAndCardIdsInOrder() {

        CandidateCardFixtures cards = new CandidateCardFixtures(jdbc);
        UUID draftJobId = cards.insertDraftJob(myUserId, myRepoId, "QUEUED");
        UUID otherJobId = cards.insertDraftJob(myUserId, myRepoId, "QUEUED");
        UUID first = cards.cardPublicIdOf(cards.insertCandidateCard(myUserId, myRepoId, null, draftJobId, false));
        cards.insertCandidateCard(myUserId, myRepoId, null, otherJobId, false);
        UUID second = cards.cardPublicIdOf(cards.insertCandidateCard(myUserId, myRepoId, null, draftJobId, false));
        UUID repoPublicId = jdbc.queryForObject("SELECT public_id FROM user_repository WHERE id = ?", UUID.class, myRepoId);
        JobResult expected = new JobResult(repoPublicId.toString(), List.of(first.toString(), second.toString()), null, null);

        assertThat(service.load(draftJobId, myUserId, LOGIN).result()).isEqualTo(expected);

        jdbc.update("UPDATE analysis_job SET state = 'SUCCEEDED', finished_at = now() WHERE public_id = ?", draftJobId);
        em.clear();

        assertThat(service.load(draftJobId, myUserId, LOGIN).result()).isEqualTo(expected);

    }

    @Test
    @DisplayName("남의 Job 은 공개 id 가 맞아도 찾을 수 없다")
    void doesNotLoadOthersJob() {

        AnalysisJob job = persistJob(othersUserId, othersRepoId, KEY_1);

        assertThatThrownBy(() -> service.load(job.getPublicId(), myUserId, LOGIN))
                .isInstanceOf(JobNotFoundException.class);

    }

    @Test
    @DisplayName("없는 공개 id 로 찾으면 찾을 수 없다")
    void doesNotLoadUnknownJob() {

        assertThatThrownBy(() -> service.load(UUID.randomUUID(), myUserId, LOGIN))
                .isInstanceOf(JobNotFoundException.class);

    }

    @Test
    @DisplayName("남의 Job 을 찾으면 요청한 사람과 그 Job 의 내부 id 로 VIEW_JOB 거절 기록을 남긴다")
    void recordsDeniedViewOfOthersJob() {

        AnalysisJob job = persistJob(othersUserId, othersRepoId, KEY_1);

        assertThatThrownBy(() -> service.load(job.getPublicId(), myUserId, LOGIN))
                .isInstanceOf(JobNotFoundException.class);

        verify(auditLog).denied(myUserId, AuditAction.VIEW_JOB, job.getId());

    }

    @Test
    @DisplayName("내 Job 이나 없는 공개 id 로 찾으면 거절 기록을 남기지 않는다")
    void doesNotRecordMineOrUnknown() {

        AnalysisJob mine = persistJob(myUserId, myRepoId, KEY_1);

        service.load(mine.getPublicId(), myUserId, LOGIN);
        assertThatThrownBy(() -> service.load(UUID.randomUUID(), myUserId, LOGIN))
                .isInstanceOf(JobNotFoundException.class);

        verifyNoInteractions(auditLog);

    }

    @Test
    @DisplayName("진행 중 Job 은 terminal 이 false, 끝난 Job 은 true 다")
    void terminalFollowsState() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);

        assertThat(service.load(job.getPublicId(), myUserId, LOGIN).terminal()).isFalse();

        job.start();
        job.succeed(false, null);
        em.flush();

        assertThat(service.load(job.getPublicId(), myUserId, LOGIN).terminal()).isTrue();

    }

    @Test
    @DisplayName("GitHub 한도로 실패한 Job 은 다시 시도할 수 있고 INTERNAL_ERROR 로 실패한 Job 은 없다")
    void retryableComesFromErrorCode() {

        AnalysisJob rateLimited = persistJob(myUserId, myRepoId, KEY_1);
        rateLimited.start();
        rateLimited.fail(JobErrorCode.GITHUB_RATE_LIMITED);
        em.flush();

        assertThat(service.load(rateLimited.getPublicId(), myUserId, LOGIN).retryable()).isTrue();

        AnalysisJob internalError = persistJob(myUserId, myRepoId, KEY_2);
        internalError.start();
        internalError.fail(JobErrorCode.INTERNAL_ERROR);
        em.flush();

        assertThat(service.load(internalError.getPublicId(), myUserId, LOGIN).retryable()).isFalse();

    }

    @Test
    @DisplayName("토큰 거절로 실패한 Job 은 저장되고, 다시 해도 같아 다시 시도할 수 없다")
    void rejectedTokenJobIsNotRetryable() {

        AnalysisJob rejected = persistJob(myUserId, myRepoId, KEY_1);
        rejected.start();
        rejected.fail(JobErrorCode.GITHUB_UNAUTHORIZED);
        em.flush();
        em.clear();

        assertThat(service.load(rejected.getPublicId(), myUserId, LOGIN).errorCode()).isEqualTo(JobErrorCode.GITHUB_UNAUTHORIZED);
        assertThat(service.load(rejected.getPublicId(), myUserId, LOGIN).retryable()).isFalse();

    }

    @Test
    @DisplayName("한도로 일부만 읽고 끝난 Job 은 다시 시도할 수 있고 저장된 남은 초가 그대로 나온다")
    void rateLimitedPartialJobShowsRetryAfterSec() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);
        job.start();
        job.succeed(true, JobErrorCode.GITHUB_RATE_LIMITED, Instant.now().plusSeconds(300));
        em.flush();

        JobView found = service.load(job.getPublicId(), myUserId, LOGIN);

        assertThat(found.retryable()).isTrue();
        assertThat(found.retryAfterSec()).isEqualTo(job.getRetryAfterSec()).isBetween(299, 300);

    }

    @Test
    @DisplayName("한도로 일부만 읽었지만 기다릴 시간을 모르면 다시 시도할 수 있고 남은 초는 비어 있다")
    void rateLimitedPartialJobWithoutResetStaysRetryable() {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);
        job.start();
        job.succeed(true, JobErrorCode.GITHUB_RATE_LIMITED);
        em.flush();

        JobView found = service.load(job.getPublicId(), myUserId, LOGIN);

        assertThat(found.retryable()).isTrue();
        assertThat(found.retryAfterSec()).isNull();

    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "GITHUB_UNAVAILABLE", "CANCELED", "COMPLETE"})
    @DisplayName("한도 부분 완료가 아닌 Job 의 남은 초는 늘 비어 있다")
    void otherJobsHaveNoRetryAfterSec(String situation) {

        AnalysisJob job = persistJob(myUserId, myRepoId, KEY_1);
        switch (situation) {
            case "RUNNING" -> job.start();
            case "GITHUB_UNAVAILABLE" -> {
                job.start();
                job.fail(JobErrorCode.GITHUB_UNAVAILABLE);
            }
            case "CANCELED" -> job.cancel();
            case "COMPLETE" -> {
                job.start();
                job.succeed(false, null);
            }
            default -> { }
        }
        em.flush();

        assertThat(service.load(job.getPublicId(), myUserId, LOGIN).retryAfterSec()).isNull();

    }

    @Test
    @DisplayName("상한에 걸려 일부만 읽고 끝난 Job 은 다시 시도할 수 없고 기다릴 시간도 없다")
    void cappedJobIsNotRetryable() {

        AnalysisJob capped = persistJob(myUserId, myRepoId, KEY_1);
        capped.start();
        capped.succeed(true, JobErrorCode.COLLECTION_CAPPED);
        em.flush();

        JobView found = service.load(capped.getPublicId(), myUserId, LOGIN);

        assertThat(found.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(found.partial()).isTrue();
        assertThat(found.errorCode()).isEqualTo(JobErrorCode.COLLECTION_CAPPED);
        assertThat(found.retryable()).isFalse();
        assertThat(found.retryAfterSec()).isNull();

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
        job.succeed(false, null);
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
