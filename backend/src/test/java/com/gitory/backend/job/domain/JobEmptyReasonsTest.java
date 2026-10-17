package com.gitory.backend.job.domain;

import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CandidateCardQueryService;
import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.ingest.domain.CollectionStatsService;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.LastAnalyzedService;
import com.gitory.backend.ingest.domain.RepositoryOwnershipService;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.job.infra.UsedCardAdapter;
import com.gitory.backend.recommend.domain.CandidateBoardService;
import com.gitory.backend.recommend.domain.CandidateEmptyReasons;
import com.gitory.backend.support.AnalyzeJobFixtures;
import com.gitory.backend.support.BoardVerdictFixtures;
import com.gitory.backend.support.CandidateBoardFixtures;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({JobQueryService.class, JobCancelService.class, JobViewFactory.class, CandidateEmptyReasons.class,
        CollectionStatsService.class, CandidateBoardService.class,
        RepositoryOwnershipService.class, RepositoryReferenceService.class, LastAnalyzedService.class,
        CommitSummaryService.class, UsedCardAdapter.class, CandidateCardQueryService.class,
        GeneratedCardQueryService.class})
class JobEmptyReasonsTest {

    private static final String LOGIN = "grow22";
    private static final UUID MY_REPO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant ANALYZED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant COUNTED_AT = Instant.parse("2026-10-02T00:00:00Z");
    private static final String KEY_1 = "11111111-1111-4111-8111-111111111111";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    JobQueryService query;

    @Autowired
    JobCancelService cancel;

    @Autowired
    CandidateBoardService board;

    @Autowired
    EntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AuditLog auditLog;

    private AnalyzeJobFixtures jobs;
    private BoardVerdictFixtures verdict;
    private Long userId;
    private Long myRepoId;
    private Long runId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        CandidateBoardFixtures board = new CandidateBoardFixtures(jdbc);
        jobs = new AnalyzeJobFixtures(em);
        verdict = new BoardVerdictFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, "grow22");
        Long repositoryId = fixtures.insertRepository(1L, "grow22", "gitory");
        myRepoId = fixtures.insertUserRepository(MY_REPO, userId, repositoryId);
        runId = verdict.insertRun(myRepoId, null, 3, 1, Instant.parse("2026-10-01T00:00:00Z"));
        board.setLastAnalyzedAt(myRepoId, ANALYZED_AT);
        board.insertGrouping(myRepoId, ANALYZED_AT.plusSeconds(1));
        verdict.setCounts(myRepoId, COUNTED_AT, 197, 52, 58, 23, 11);
        verdict.insertCommit(repositoryId, "a".repeat(40), "grow22", "feat: 세션 만료 처리", null);
        verdict.insertCommit(repositoryId, "b".repeat(40), null, "계정 없는 커밋", null);

    }

    @Test
    @DisplayName("후보가 0개인 분석 Job 조회는 같은 저장소 연결의 후보 보드와 같은 이유 줄을 준다")
    void returnsBoardLinesThroughGet() {

        AnalysisJob job = jobs.groupedJob(userId, myRepoId, KEY_1, runId, 0, false);

        JobResult result = query.load(job.getPublicId(), userId, LOGIN).result();

        List<String> boardLines = board.load(MY_REPO, userId, "grow22").emptyReasons();
        assertThat(boardLines).hasSize(4);
        assertThat(result.verdict()).isEqualTo("EMPTY");
        assertThat(result.reasons()).isEqualTo(boardLines);

    }

    @Test
    @DisplayName("이미 끝난 후보 0개 분석 Job 을 취소해도 응답의 이유 줄은 조회와 같다")
    void returnsBoardLinesThroughCancelOfFinishedJob() {

        AnalysisJob job = jobs.groupedJob(userId, myRepoId, KEY_1, runId, 0, true);

        JobResult result = cancel.cancel(job.getPublicId(), userId, LOGIN).result();

        assertThat(result.verdict()).isEqualTo("EMPTY");
        assertThat(result.reasons()).isEqualTo(board.load(MY_REPO, userId, "grow22").emptyReasons());
        assertThat(result.reasons()).isEqualTo(query.load(job.getPublicId(), userId, LOGIN).result().reasons());

    }

    @Test
    @DisplayName("후보가 있는 분석 Job 은 OK 와 PARTIAL 모두 이유 줄이 빈 목록이다")
    void returnsEmptyListForOkAndPartial() {

        AnalysisJob ok = jobs.groupedJob(userId, myRepoId, KEY_1, runId, 3, false);
        AnalysisJob partial = jobs.groupedJob(userId, myRepoId, "22222222-2222-4222-8222-222222222222", runId, 1,
                true);

        assertThat(query.load(ok.getPublicId(), userId, LOGIN).result())
                .isEqualTo(new JobResult(MY_REPO.toString(), null, "OK", List.of()));
        assertThat(cancel.cancel(partial.getPublicId(), userId, LOGIN).result())
                .isEqualTo(new JobResult(MY_REPO.toString(), null, "PARTIAL", List.of()));

    }

    @Test
    @DisplayName("경험 그룹화를 건너뛴 분석 Job 은 판정과 이유 줄이 그대로 null 이다")
    void leavesNullVerdictUnchanged() {

        AnalysisJob job = jobs.ungroupedJob(userId, myRepoId, KEY_1, runId);

        assertThat(query.load(job.getPublicId(), userId, LOGIN).result())
                .isEqualTo(new JobResult(MY_REPO.toString(), null, null, null));

    }

    @Test
    @DisplayName("초안(DRAFT) Job 의 결과에는 이유 줄이 없다")
    void givesDraftJobNoReasonLines() {

        AnalysisJob job = jobs.groupedJob(userId, myRepoId, KEY_1, runId, 0, false);
        jdbc.update("UPDATE analysis_job SET type = 'DRAFT' WHERE public_id = ?", job.getPublicId());
        em.clear();

        JobResult result = query.load(job.getPublicId(), userId, LOGIN).result();

        assertThat(Optional.ofNullable(result).map(JobResult::reasons).orElse(List.of())).isEmpty();

    }
}
