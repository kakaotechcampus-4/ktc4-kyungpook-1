package com.gitory.backend.job.domain;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.LastAnalyzedService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ClockConfig.class, RepoRefreshService.class, JobIntakeService.class, LastAnalyzedService.class,
        RepositoryOwnershipService.class, AuditLog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepoRefreshServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RepoRefreshService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GithubCollectionAccessPort githubAccess;

    private Instant now;
    private Long userId;
    private Long otherUserId;
    private int repoSeq;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        otherUserId = fixtures.insertUser(2L, "someone");
        now = Instant.now();
        repoSeq = 0;

    }

    @Test
    @DisplayName("정리한 적이 있고 마지막 push 가 마지막 정리보다 뒤인 레포만 접수하며 GitHub 은 새로 부르지 않는다")
    void checksOnlyRepositoriesPushedAfterTheLastAnalysis() {

        Long stale = repo(userId, "stale", ago(20), ago(10));
        repo(userId, "fresh", ago(10), ago(20));
        repo(userId, "never-analyzed", null, ago(10));
        repo(userId, "no-push", ago(20), null);

        assertThat(service.check(userId)).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT user_repository_id FROM analysis_job", Long.class)).containsExactly(stale);
        verify(githubAccess).requireUsableConnection(userId);
        verifyNoMoreInteractions(githubAccess);

    }

    @Test
    @DisplayName("쓸 수 있는 GitHub 연결이 없으면 아무것도 접수하지 않고 오류 없이 0 을 돌려준다")
    void returnsZeroWithoutConnection() {

        repo(userId, "stale", ago(20), ago(10));
        doThrow(new GithubNotConnectedException()).when(githubAccess).requireUsableConnection(userId);

        assertThat(service.check(userId)).isZero();

        assertThat(count("analysis_job")).isZero();
        assertThat(count("repo_refresh")).isZero();

    }

    @Test
    @DisplayName("한 번에 push 가 최근인 순서로 3개 레포까지만 접수한다")
    void queuesAtMostThreeMostRecentlyPushed() {

        repo(userId, "r1", ago(50), ago(40));
        repo(userId, "r2", ago(50), ago(30));
        Long third = repo(userId, "r3", ago(50), ago(20));
        Long fourth = repo(userId, "r4", ago(50), ago(10));
        Long newest = repo(userId, "r5", ago(50), ago(5));

        assertThat(service.check(userId)).isEqualTo(3);

        assertThat(jdbc.queryForList("SELECT user_repository_id FROM analysis_job", Long.class))
                .containsExactlyInAnyOrder(newest, fourth, third);

    }

    @Test
    @DisplayName("진행 중인 분석 Job 이 있는 레포는 접수하지 않고 건너뛴 자리를 다음 레포가 채운다")
    void skipsRepositoryWithActiveAnalysis() {

        Long busy = repo(userId, "busy", ago(50), ago(5));
        Long other = repo(userId, "other", ago(50), ago(10));
        jdbc.update("INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, 'ANALYZE', 'RUNNING')",
                userId, busy, UUID.randomUUID().toString());

        assertThat(service.check(userId)).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT user_repository_id FROM repo_refresh", Long.class)).containsExactly(other);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_job WHERE user_repository_id = ?", Integer.class, busy))
                .isEqualTo(1);

    }

    @Test
    @DisplayName("마지막 스스로 다시 읽기 기록을 만든 지 6시간이 안 된 레포는 접수하지 않고 6시간이 지났으면 접수한다")
    void skipsRepositoryRefreshedWithinSixHours() {

        Long recent = repo(userId, "recent", ago(50), ago(5));
        Long old = repo(userId, "old", ago(50), ago(10));
        insertRefresh(userId, recent, "CLOSED", 5 * 3600);
        insertRefresh(userId, old, "CLOSED", 7 * 3600);

        assertThat(service.check(userId)).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT user_repository_id FROM repo_refresh WHERE state = 'READING'", Long.class))
                .containsExactly(old);

    }

    @Test
    @DisplayName("접수하면 분석 Job 을 가리키고 접수 직전의 마지막 정리 시각을 담은 READING 기록 한 줄을 남긴다")
    void recordsReadingRowForTheQueuedJob() {

        Instant lastAnalyzedAt = ago(20).truncatedTo(ChronoUnit.MICROS);
        Long stale = repo(userId, "stale", lastAnalyzedAt, ago(10));

        service.check(userId);

        Map<String, Object> job = jdbc.queryForMap("SELECT id, type, state FROM analysis_job");
        Map<String, Object> refresh = jdbc.queryForMap("SELECT * FROM repo_refresh");
        assertThat(job.get("type")).isEqualTo("ANALYZE");
        assertThat(job.get("state")).isEqualTo("QUEUED");
        assertThat(refresh.get("state")).isEqualTo("READING");
        assertThat(refresh.get("user_id")).isEqualTo(userId);
        assertThat(refresh.get("user_repository_id")).isEqualTo(stale);
        assertThat(refresh.get("analysis_job_id")).isEqualTo(job.get("id"));
        assertThat(refresh.get("draft_job_id")).isNull();
        assertThat(((Timestamp) refresh.get("previous_analyzed_at")).toInstant()).isEqualTo(lastAnalyzedAt);
        assertThat(((Number) refresh.get("card_count")).intValue()).isZero();

    }

    @Test
    @DisplayName("알림 목록은 내 보지 않은 READY 기록만 레포마다 하나로 묶어 카드 수는 합하고 가장 늦게 끝난 시각으로 최근 순으로 준다")
    void listsUnseenReadyGroupedByRepository() {

        Long repoA = repo(userId, "repo-a", ago(30), ago(5));
        Long repoB = repo(userId, "repo-b", ago(30), ago(5));
        Long othersRepo = repo(otherUserId, "others", ago(30), ago(5));
        insertReady(userId, repoA, 2, 120, false);
        insertReady(userId, repoA, 3, 60, false);
        insertReady(userId, repoA, 9, 300, true);
        insertReady(userId, repoB, 4, 30, false);
        insertRefresh(userId, repoB, "DRAFTING", 90 * 60);
        insertRefresh(userId, repoB, "CLOSED", 90 * 60);
        insertReady(otherUserId, othersRepo, 7, 10, false);

        assertThat(service.updates(userId))
                .extracting(RepoUpdateRow::repoName, RepoUpdateRow::cardCount, RepoUpdateRow::repoId)
                .containsExactly(
                        tuple("grow22/repo-b", 4, publicIdOf(repoB)),
                        tuple("grow22/repo-a", 5, publicIdOf(repoA)));
        assertThat(service.updates(userId).getFirst().finishedAt())
                .isAfter(service.updates(userId).getLast().finishedAt());

    }

    @Test
    @DisplayName("레포의 알림을 본 것으로 표시하면 그 레포의 보지 않은 READY 기록이 모두 닫혀 목록에 다시 나오지 않고 다른 레포는 그대로다")
    void markSeenClosesAllUnseenReadyOfTheRepository() {

        Long repoA = repo(userId, "repo-a", ago(30), ago(5));
        Long repoB = repo(userId, "repo-b", ago(30), ago(5));
        insertReady(userId, repoA, 2, 120, false);
        insertReady(userId, repoA, 3, 60, false);
        insertReady(userId, repoB, 4, 30, false);

        service.markSeen(publicIdOf(repoA), userId);

        assertThat(service.updates(userId)).extracting(RepoUpdateRow::repoName).containsExactly("grow22/repo-b");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repo_refresh WHERE seen_at IS NOT NULL AND state = 'READY'",
                Integer.class)).isEqualTo(2);

    }

    @Test
    @DisplayName("남의 레포나 없는 레포의 알림을 닫으려 하면 없는 레포로 거절하고 아무것도 바꾸지 않는다")
    void markSeenRejectsOthersAndMissingRepository() {

        Long othersRepo = repo(otherUserId, "others", ago(30), ago(5));
        insertReady(otherUserId, othersRepo, 7, 10, false);

        assertThatThrownBy(() -> service.markSeen(publicIdOf(othersRepo), userId))
                .isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> service.markSeen(UUID.randomUUID(), userId))
                .isInstanceOf(RepositoryNotFoundException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM repo_refresh WHERE seen_at IS NOT NULL", Integer.class))
                .isZero();

    }

    private Instant ago(int hours) {

        return now.minus(hours, ChronoUnit.HOURS);

    }

    private Long repo(Long ownerId, String name, Instant lastAnalyzedAt, Instant pushedAt) {

        long githubRepoId = ++repoSeq;
        Long repositoryId = jdbc.queryForObject(
                "INSERT INTO repository (github_repo_id, owner_login, name, visibility, github_pushed_at) VALUES (?, 'grow22', ?, 'PUBLIC', ?) RETURNING id",
                Long.class, githubRepoId, name, pushedAt == null ? null : Timestamp.from(pushedAt));

        return jdbc.queryForObject(
                "INSERT INTO user_repository (user_id, repository_id, last_analyzed_at) VALUES (?, ?, ?) RETURNING id",
                Long.class, ownerId, repositoryId, lastAnalyzedAt == null ? null : Timestamp.from(lastAnalyzedAt));

    }

    private UUID publicIdOf(Long userRepositoryId) {

        return jdbc.queryForObject("SELECT public_id FROM user_repository WHERE id = ?", UUID.class, userRepositoryId);

    }

    private int count(String table) {

        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

    }

    private Long insertJob(Long ownerId, Long userRepositoryId, String type, String state) {

        return jdbc.queryForObject(
                "INSERT INTO analysis_job (user_id, user_repository_id, idempotency_key, type, state) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, ownerId, userRepositoryId, UUID.randomUUID().toString(), type, state);

    }

    // DRAFTING 과 READY 는 초안 Job 을 가리켜야 하고, 끝난 기록은 끝난 시각이 있어야 한다
    private void insertRefresh(Long ownerId, Long userRepositoryId, String state, int createdSecondsAgo) {

        insertRefresh(ownerId, userRepositoryId, state, createdSecondsAgo, 0, false);

    }

    private void insertReady(Long ownerId, Long userRepositoryId, int cardCount, int finishedMinutesAgo, boolean seen) {

        insertRefresh(ownerId, userRepositoryId, "READY", finishedMinutesAgo * 60, cardCount, seen);

    }

    private void insertRefresh(Long ownerId, Long userRepositoryId, String state, int createdSecondsAgo,
                               int cardCount, boolean seen) {

        Long analysisJobId = insertJob(ownerId, userRepositoryId, "ANALYZE", "SUCCEEDED");
        boolean drafted = state.equals("DRAFTING") || state.equals("READY");
        Long draftJobId = drafted ? insertJob(ownerId, userRepositoryId, "DRAFT", "SUCCEEDED") : null;
        boolean running = state.equals("READING") || state.equals("DRAFTING");
        Timestamp created = Timestamp.from(now.minusSeconds(createdSecondsAgo));
        jdbc.update("""
                        INSERT INTO repo_refresh (user_id, user_repository_id, analysis_job_id, draft_job_id,
                            previous_analyzed_at, card_count, state, created_at, finished_at, seen_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ownerId, userRepositoryId, analysisJobId, draftJobId, created, cardCount, state, created,
                running ? null : created, seen ? created : null);

    }
}
