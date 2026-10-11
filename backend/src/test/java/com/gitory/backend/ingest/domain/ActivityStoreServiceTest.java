package com.gitory.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.gitory.backend.ingest.infra.CollectionRunRepository;
import com.gitory.backend.ingest.infra.GitCommitRepository;
import com.gitory.backend.ingest.infra.IssueRepository;
import com.gitory.backend.ingest.infra.PrReviewCommentRepository;
import com.gitory.backend.ingest.infra.PrReviewRepository;
import com.gitory.backend.ingest.infra.PullRequestRepository;
import com.gitory.backend.ingest.port.CollectedActivity;
import com.gitory.backend.ingest.port.CollectedCommit;
import com.gitory.backend.ingest.port.CollectedIssue;
import com.gitory.backend.ingest.port.CollectedPullRequest;
import com.gitory.backend.ingest.port.CollectedReview;
import com.gitory.backend.ingest.port.CollectedReviewComment;
import com.gitory.backend.ingest.port.IngestRequest;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ActivityStoreService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityStoreServiceTest {

    private static final String SHA_1 = "1111111111111111111111111111111111111111";
    private static final String SHA_2 = "2222222222222222222222222222222222222222";
    private static final List<String> BRANCHES = List.of("develop", "feature/session-index");
    private static final Instant PR_OPENED_AT = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant ISSUE_OPENED_AT = Instant.parse("2026-09-19T08:00:00Z");
    // 실제 GitHub 리뷰·댓글 id 처럼 INT 를 넘는 값
    private static final long REVIEW_ID = 5_417_303_905L;
    private static final long COMMENT_ID = 5_417_303_906L;
    private static final Instant REVIEWED_AT = Instant.parse("2026-09-20T12:00:00Z");
    private static final Instant COMMENTED_AT = Instant.parse("2026-09-20T11:50:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ActivityStoreService service;

    @Autowired
    CollectionRunRepository runs;

    @Autowired
    GitCommitRepository commits;

    @Autowired
    PullRequestRepository pullRequests;

    @Autowired
    IssueRepository issues;

    @Autowired
    PrReviewRepository reviews;

    @Autowired
    PrReviewCommentRepository reviewComments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    private Long userRepositoryId;
    private Long repositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();

        Long userId = fixtures.insertUser(1L, "grow22");

        // 저장소를 둘 만들어 user_repository.id 와 repository.id 를 다르게 한다.
        // 두 번호가 같으면 엉뚱한 번호를 넣어도 테스트가 통과한다
        fixtures.insertRepository(1L, "grow22", "other");
        repositoryId = fixtures.insertRepository(2L, "grow22", "gitory");

        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
    }

    @Test
    @DisplayName("수집 이력과 커밋의 필드마다 맞는 값이 저장된다")
    void savesRunAndCommit() {

        CollectedPullRequest pr = new CollectedPullRequest(17, "세션 조회 성능 개선", null, GithubState.CLOSED, "grow22",
                "develop", "feature/session-index", Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-21T11:00:00Z"), List.of(SHA_1), List.of(42), List.of());
        CollectedIssue issue = new CollectedIssue(42, "세션 조회가 느립니다", null, GithubState.CLOSED, "reporter",
                List.of(), Instant.parse("2026-09-19T08:00:00Z"), Instant.parse("2026-09-21T11:00:00Z"));
        Long runId = service.store(request(), new CollectedActivity(List.of(commit(SHA_1)), List.of(pr),
                List.of(issue), "a".repeat(40), PartialReason.CAP_EXCEEDED));

        CollectionRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getBranches()).containsExactly("develop", "feature/session-index");
        assertThat(run.getSince()).isNull();
        assertThat(run.getHeadSha()).isEqualTo("a".repeat(40));
        assertThat(run.getCommitsCollected()).isEqualTo(1);
        assertThat(run.getPrsCollected()).isEqualTo(1);
        assertThat(run.getIssuesCollected()).isEqualTo(1);
        assertThat(run.getPartialReason()).isEqualTo(PartialReason.CAP_EXCEEDED);

        GitCommit stored = commits.findByRepositoryIdAndSha(repositoryId, SHA_1).orElseThrow();
        assertThat(stored.getCollectionRunId()).isEqualTo(runId);
        assertThat(stored.getAuthorLogin()).isEqualTo("grow22");
        assertThat(stored.getAuthorName()).isEqualTo("Grow");
        assertThat(stored.getMessage()).isEqualTo("feat: 세션 인덱스 추가");
        assertThat(stored.getAuthoredAt()).isEqualTo(Instant.parse("2026-09-20T09:00:00Z"));
        assertThat(stored.getAdditions()).isEqualTo(11);
        assertThat(stored.getDeletions()).isEqualTo(22);
        assertThat(stored.getChangedFiles()).isEqualTo(33);
        assertThat(stored.getParentCount()).isEqualTo((short) 1);
        assertThat(stored.isExcluded()).isFalse();
    }

    @Test
    @DisplayName("커밋에는 user_repository_id 가 아니라 repository_id 가 저장된다")
    void savesRepositoryIdNotUserRepositoryId() {

        service.store(request(), activityOf(commit(SHA_1)));

        Long saved = jdbc.queryForObject(
                "SELECT repository_id FROM git_commit WHERE sha = ?", Long.class, SHA_1);

        assertThat(saved).isEqualTo(repositoryId).isNotEqualTo(userRepositoryId);
    }

    @Test
    @DisplayName("이미 저장한 커밋은 다시 저장되지 않지만 개수에는 그대로 센다")
    void skipsCommitStoredBefore() {

        service.store(request(), activityOf(commit(SHA_1)));
        Long secondRunId = service.store(request(), activityOf(commit(SHA_1), commit(SHA_2)));

        assertThat(countCommits()).isEqualTo(2);
        assertThat(runs.findById(secondRunId).orElseThrow().getCommitsCollected()).isEqualTo(2);
    }

    @Test
    @DisplayName("재수집 요청에는 이 연결 저장소에서 이미 수집한 commit SHA를 제공한다")
    void providesKnownCommitShas() {

        assertThat(service.knownCommitShas(userRepositoryId)).isEmpty();

        service.store(request(), activityOf(commit(SHA_1), commit(SHA_2)));

        assertThat(service.knownCommitShas(userRepositoryId)).containsExactly(SHA_1, SHA_2);
    }

    @Test
    @DisplayName("한 응답에 같은 커밋이 두 번 있으면 한 번만 저장된다")
    void skipsDuplicateShaInOneResponse() {

        Long runId = service.store(request(), activityOf(commit(SHA_1), commit(SHA_1)));

        assertThat(countCommits()).isEqualTo(1);
        assertThat(runs.findById(runId).orElseThrow().getCommitsCollected()).isEqualTo(2);
    }

    @Test
    @DisplayName("커밋이 하나도 없어도 수집 이력은 남는다")
    void savesRunWithoutCommits() {

        Long runId = service.store(request(), activityOf());

        assertThat(runs.findById(runId)).isPresent();
        assertThat(countCommits()).isZero();
    }

    @Test
    @DisplayName("수집 결과를 저장하면 그 시각이 연결 저장소의 마지막 분석 시각으로 남는다")
    void marksLastAnalyzedAt() {

        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        service.store(request(), activityOf(commit(SHA_1)));

        Timestamp analyzedAt = jdbc.queryForObject(
                "SELECT last_analyzed_at FROM user_repository WHERE id = ?", Timestamp.class, userRepositoryId);
        assertThat(analyzedAt.toInstant()).isAfterOrEqualTo(before);

    }

    @Test
    @DisplayName("작성자 로그인이 없고 제외 사유가 있는 커밋도 받은 값 그대로 저장된다")
    void savesExcludedCommitWithoutLogin() {

        service.store(request(), activityOf(new CollectedCommit(SHA_1, null, "dependabot", "chore: 의존성 올림",
                Instant.parse("2026-09-20T09:00:00Z"), 1, 1, 1, (short) 1, ExclusionReason.BOT)));

        GitCommit stored = commits.findByRepositoryIdAndSha(repositoryId, SHA_1).orElseThrow();
        assertThat(stored.getAuthorLogin()).isNull();
        assertThat(stored.isExcluded()).isTrue();
        assertThat(stored.getExclusionReason()).isEqualTo(ExclusionReason.BOT);

    }

    @Test
    @DisplayName("같은 저장소의 수집 결과를 두 Job 이 동시에 저장해도 둘 다 성공하고 커밋은 한 번씩만 저장된다")
    void concurrentStoresOfSameRepositoryBothSucceed() throws Exception {

        Long teammateRepositoryId = connectTeammate();
        CountDownLatch firstStoredBeforeCommit = new CountDownLatch(1);
        ExecutorService firstJob = Executors.newSingleThreadExecutor();

        try {
            Future<Long> first = firstJob.submit(() -> transaction.execute(status -> {
                Long runId = service.store(request(), activityOf(commit(SHA_1), commit(SHA_2)));
                firstStoredBeforeCommit.countDown();
                await().atMost(5, TimeUnit.SECONDS).until(this::anotherStoreIsWaiting);
                return runId;
            }));
            firstStoredBeforeCommit.await(5, TimeUnit.SECONDS);

            Long secondRunId = service.store(new IngestRequest(teammateRepositoryId, BRANCHES, List.of()),
                    activityOf(commit(SHA_1), commit(SHA_2)));

            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(secondRunId).isNotNull();
            assertThat(runs.count()).isEqualTo(2);
            assertThat(countCommits()).isEqualTo(2);
        } finally {
            firstJob.shutdown();
        }

    }

    @Test
    @DisplayName("PR·이슈의 칸마다 받은 값이 저장되고 이번 수집 이력과 저장소가 연결된다")
    void savesPullRequestAndIssueDetails() {

        Long runId = service.store(request(), detailsOf(List.of(mergedPullRequest(17)), List.of(closedIssue(42))));

        assertPullRequestStored(17, mergedPullRequest(17), runId);
        assertIssueStored(42, closedIssue(42), runId);

    }

    @Test
    @DisplayName("본문·작성자·머지 시각·닫힌 시각이 없는 PR·이슈도 그 칸을 비운 채 저장된다")
    void savesPullRequestAndIssueWithoutOptionalValues() {

        CollectedPullRequest pr = new CollectedPullRequest(17, "세션 조회 성능 개선", null, GithubState.OPEN, null,
                "develop", "feature/session-index", PR_OPENED_AT, null, List.of(), List.of(), List.of());
        CollectedIssue issue = new CollectedIssue(42, "세션 조회가 느립니다", null, GithubState.OPEN, null, List.of(),
                ISSUE_OPENED_AT, null);

        Long runId = service.store(request(), detailsOf(List.of(pr), List.of(issue)));

        assertPullRequestStored(17, pr, runId);
        assertIssueStored(42, issue, runId);

    }

    @Test
    @DisplayName("같은 PR 을 다시 수집하면 같은 행이 이번 수집 값과 이번 수집 이력으로 바뀐다")
    void overwritesPullRequestCollectedAgain() {

        service.store(request(), detailsOf(List.of(openPullRequest(17)), List.of()));
        Long firstId = storedPullRequest(17).getId();

        Long secondRunId = service.store(request(), detailsOf(List.of(mergedPullRequest(17)), List.of()));

        assertThat(countPullRequests()).isEqualTo(1);
        assertThat(storedPullRequest(17).getId()).isEqualTo(firstId);
        assertPullRequestStored(17, mergedPullRequest(17), secondRunId);

    }

    @Test
    @DisplayName("같은 이슈를 다시 수집하면 같은 행이 이번 수집 값과 이번 수집 이력으로 바뀐다")
    void overwritesIssueCollectedAgain() {

        service.store(request(), detailsOf(List.of(), List.of(openIssue(42))));
        Long firstId = storedIssue(42).getId();

        Long secondRunId = service.store(request(), detailsOf(List.of(), List.of(closedIssue(42))));

        assertThat(countIssues()).isEqualTo(1);
        assertThat(storedIssue(42).getId()).isEqualTo(firstId);
        assertIssueStored(42, closedIssue(42), secondRunId);

    }

    @Test
    @DisplayName("다음 수집 응답에 없는 PR·이슈는 지우지 않고 앞서 저장한 값 그대로 둔다")
    void keepsPullRequestAndIssueMissingFromLaterCollection() {

        Long firstRunId = service.store(request(), detailsOf(List.of(openPullRequest(17), openPullRequest(18)),
                List.of(openIssue(42), openIssue(43))));

        service.store(request(), detailsOf(List.of(mergedPullRequest(17)), List.of(closedIssue(42))));

        assertThat(countPullRequests()).isEqualTo(2);
        assertThat(countIssues()).isEqualTo(2);
        assertPullRequestStored(18, openPullRequest(18), firstRunId);
        assertIssueStored(43, openIssue(43), firstRunId);

    }

    @Test
    @DisplayName("PR 하나를 저장하지 못하면 같은 수집의 수집 이력·커밋·PR·이슈가 하나도 남지 않는다")
    void storesNothingWhenPullRequestCannotBeStored() {

        CollectedPullRequest tooLongTitle = new CollectedPullRequest(18, "가".repeat(257), null, GithubState.OPEN,
                "grow22", "develop", "feature/too-long", PR_OPENED_AT, null, List.of(), List.of(), List.of());
        CollectedActivity activity = new CollectedActivity(List.of(commit(SHA_1)),
                List.of(openPullRequest(17), tooLongTitle), List.of(openIssue(42)), null, null);

        assertThatThrownBy(() -> service.store(request(), activity)).isInstanceOf(DataAccessException.class);

        assertThat(runs.count()).isZero();
        assertThat(countCommits()).isZero();
        assertThat(countPullRequests()).isZero();
        assertThat(countIssues()).isZero();

    }

    @Test
    @DisplayName("이슈 하나를 저장하지 못하면 먼저 저장한 수집 이력·커밋·PR 까지 모두 되돌린다")
    void storesNothingWhenIssueCannotBeStored() {

        CollectedIssue tooLongTitle = new CollectedIssue(43, "가".repeat(257), null, GithubState.OPEN, "reporter",
                List.of(), ISSUE_OPENED_AT, null);
        CollectedActivity activity = new CollectedActivity(List.of(commit(SHA_1)), List.of(openPullRequest(17)),
                List.of(openIssue(42), tooLongTitle), null, null);

        assertThatThrownBy(() -> service.store(request(), activity)).isInstanceOf(DataAccessException.class);

        assertThat(runs.count()).isZero();
        assertThat(countCommits()).isZero();
        assertThat(countPullRequests()).isZero();
        assertThat(countIssues()).isZero();

    }

    @Test
    @DisplayName("두 Job 이 같은 PR·이슈를 동시에 저장해도 둘 다 성공하고, 행은 하나씩 남아 나중에 저장한 값이 된다")
    void concurrentStoresOfSamePullRequestAndIssueBothSucceed() throws Exception {

        Long teammateRepositoryId = connectTeammate();
        CountDownLatch firstStoredBeforeCommit = new CountDownLatch(1);
        ExecutorService firstJob = Executors.newSingleThreadExecutor();

        try {
            Future<Long> first = firstJob.submit(() -> transaction.execute(status -> {
                Long runId = service.store(request(), detailsOf(List.of(openPullRequest(17)), List.of(openIssue(42))));
                firstStoredBeforeCommit.countDown();
                await().atMost(5, TimeUnit.SECONDS).until(this::anotherStoreIsWaiting);
                return runId;
            }));
            firstStoredBeforeCommit.await(5, TimeUnit.SECONDS);

            Long secondRunId = service.store(new IngestRequest(teammateRepositoryId, BRANCHES, List.of()),
                    detailsOf(List.of(mergedPullRequest(17)), List.of(closedIssue(42))));

            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(countPullRequests()).isEqualTo(1);
            assertThat(countIssues()).isEqualTo(1);
            assertPullRequestStored(17, mergedPullRequest(17), secondRunId);
            assertIssueStored(42, closedIssue(42), secondRunId);
        } finally {
            firstJob.shutdown();
        }

    }

    @Test
    @DisplayName("PR·이슈는 받은 순서와 상관없이 번호가 작은 것부터 저장한다")
    void storesPullRequestsAndIssuesInNumberOrder() {

        service.store(request(), detailsOf(List.of(openPullRequest(17), openPullRequest(3), openPullRequest(9)),
                List.of(openIssue(42), openIssue(5), openIssue(11))));

        assertThat(jdbc.queryForList("SELECT github_pr_number FROM pull_request ORDER BY id", Integer.class))
                .containsExactly(3, 9, 17);
        assertThat(jdbc.queryForList("SELECT github_issue_number FROM issue ORDER BY id", Integer.class))
                .containsExactly(5, 11, 42);

    }

    @Test
    @DisplayName("처음 수집한 PR 의 리뷰·줄 댓글이 같은 저장에서 칸마다 받은 값으로 저장되고 이번 수집 이력과 저장소가 연결된다")
    void savesReviewsAndCommentsOfNewPullRequest() {

        CollectedReviewComment comment = comment(COMMENT_ID, REVIEW_ID);
        CollectedReview review = review(REVIEW_ID, comment);

        Long runId = service.store(request(), detailsOf(List.of(reviewedPullRequest(17, review)), List.of()));

        assertReviewStored(REVIEW_ID, 17, review, runId);
        assertCommentStored(COMMENT_ID, 17, comment, runId);

    }

    @Test
    @DisplayName("본문·제출 시각·작성자가 없는 리뷰와 리뷰 id·작성자·파일·줄이 없는 줄 댓글도 그 칸을 비운 채 저장된다")
    void savesReviewAndCommentWithoutOptionalValues() {

        CollectedReviewComment comment = new CollectedReviewComment(COMMENT_ID, null, null, "본문만 있는 댓글", null, null,
                COMMENTED_AT);
        CollectedReview review = new CollectedReview(REVIEW_ID, ReviewState.COMMENTED, null, null, null,
                List.of(comment));

        Long runId = service.store(request(), detailsOf(List.of(reviewedPullRequest(17, review)), List.of()));

        assertReviewStored(REVIEW_ID, 17, review, runId);
        assertCommentStored(COMMENT_ID, 17, comment, runId);

    }

    @Test
    @DisplayName("상태를 모르는 리뷰는 리뷰 행만 저장하지 않고 그 리뷰에 달린 줄 댓글은 저장한다")
    void skipsUnknownStateReviewButStoresItsComments() {

        CollectedReviewComment comment = comment(COMMENT_ID, REVIEW_ID);
        CollectedReview unknownState = new CollectedReview(REVIEW_ID, null, "reviewer", "책임 분리를 명확히 해주세요.",
                REVIEWED_AT, List.of(comment));

        Long runId = service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, unknownState, review(REVIEW_ID + 1))), List.of()));

        assertThat(jdbc.queryForList("SELECT github_review_id FROM pr_review", Long.class))
                .containsExactly(REVIEW_ID + 1);
        assertCommentStored(COMMENT_ID, 17, comment, runId);

    }

    @Test
    @DisplayName("줄 댓글 하나를 저장하지 못하면 먼저 저장한 수집 이력·커밋·PR·리뷰·줄 댓글까지 모두 되돌린다")
    void storesNothingWhenReviewCommentCannotBeStored() {

        CollectedReviewComment tooLongAuthor = new CollectedReviewComment(COMMENT_ID + 1, REVIEW_ID, "a".repeat(40),
                "인덱스 책임을 repository 계층으로 옮겨주세요.", "backend/session.sql", 12, COMMENTED_AT);
        CollectedActivity activity = new CollectedActivity(List.of(commit(SHA_1)),
                List.of(reviewedPullRequest(17, review(REVIEW_ID, comment(COMMENT_ID, REVIEW_ID), tooLongAuthor))),
                List.of(), null, null);

        assertThatThrownBy(() -> service.store(request(), activity)).isInstanceOf(DataAccessException.class);

        assertThat(runs.count()).isZero();
        assertThat(countCommits()).isZero();
        assertThat(countPullRequests()).isZero();
        assertThat(countReviews()).isZero();
        assertThat(countComments()).isZero();

    }

    @Test
    @DisplayName("같은 리뷰를 다시 수집하면 같은 행의 상태·작성자·본문·제출 시각이 이번 값과 이번 수집 이력으로 바뀐다")
    void overwritesReviewCollectedAgain() {

        service.store(request(), detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID))), List.of()));
        Long firstId = storedReview(REVIEW_ID).getId();

        Long secondRunId = service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, revisedReview(REVIEW_ID))), List.of()));

        assertThat(countReviews()).isEqualTo(1);
        assertThat(storedReview(REVIEW_ID).getId()).isEqualTo(firstId);
        assertReviewStored(REVIEW_ID, 17, revisedReview(REVIEW_ID), secondRunId);

    }

    @Test
    @DisplayName("같은 줄 댓글을 다시 수집하면 같은 행의 본문·파일·줄·작성자·리뷰 id 가 이번 값과 이번 수집 이력으로 바뀌고 작성 시각은 처음 값 그대로 남는다")
    void overwritesCommentCollectedAgain() {

        service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID, comment(COMMENT_ID, REVIEW_ID)))), List.of()));
        Long firstId = storedComment(COMMENT_ID).getId();

        CollectedReviewComment revised = revisedComment(COMMENT_ID, REVIEW_ID + 1);
        Long secondRunId = service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID + 1, revised))), List.of()));

        PrReviewComment stored = storedComment(COMMENT_ID);
        assertThat(countComments()).isEqualTo(1);
        assertThat(stored.getId()).isEqualTo(firstId);
        assertThat(stored.getCollectionRunId()).isEqualTo(secondRunId);
        assertThat(stored.getGithubReviewId()).isEqualTo(REVIEW_ID + 1);
        assertThat(stored.getAuthorLogin()).isEqualTo(revised.authorLogin());
        assertThat(stored.getBodyExcerpt()).isEqualTo(revised.bodyExcerpt());
        assertThat(stored.getPath()).isEqualTo(revised.path());
        assertThat(stored.getLine()).isEqualTo(revised.line());
        assertThat(stored.getCommentedAt()).isEqualTo(COMMENTED_AT);

    }

    @Test
    @DisplayName("다시 수집한 줄 댓글의 줄 번호가 비어 오면 저장된 줄 번호를 그대로 둔다")
    void keepsStoredLineWhenCollectedAgainWithoutLine() {

        service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID, commentOnLine(12)))), List.of()));

        Long secondRunId = service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID, commentOnLine(null)))), List.of()));

        assertThat(storedComment(COMMENT_ID).getLine()).isEqualTo(12);
        assertThat(storedComment(COMMENT_ID).getCollectionRunId()).isEqualTo(secondRunId);

    }

    @Test
    @DisplayName("다시 수집한 줄 댓글에 새 줄 번호가 오면 새 번호로 바꾼다")
    void replacesStoredLineWithNewLine() {

        service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID, commentOnLine(12)))), List.of()));

        service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID, commentOnLine(30)))), List.of()));

        assertThat(storedComment(COMMENT_ID).getLine()).isEqualTo(30);

    }

    @Test
    @DisplayName("같은 리뷰·줄 댓글이 다른 저장소의 다른 PR 에 실려 와도 처음 저장한 저장소·PR 번호는 그대로 둔다")
    void keepsPullRequestOfReviewAndCommentCollectedUnderAnotherPullRequest() {

        service.store(request(),
                detailsOf(List.of(reviewedPullRequest(17, review(REVIEW_ID, comment(COMMENT_ID, REVIEW_ID)))), List.of()));

        service.store(new IngestRequest(connectOtherRepository(), BRANCHES, List.of()),
                detailsOf(List.of(reviewedPullRequest(18, review(REVIEW_ID, comment(COMMENT_ID, REVIEW_ID)))), List.of()));

        assertThat(storedReview(REVIEW_ID).getRepositoryId()).isEqualTo(repositoryId);
        assertThat(storedReview(REVIEW_ID).getGithubPrNumber()).isEqualTo(17);
        assertThat(storedComment(COMMENT_ID).getRepositoryId()).isEqualTo(repositoryId);
        assertThat(storedComment(COMMENT_ID).getGithubPrNumber()).isEqualTo(17);

    }

    @Test
    @DisplayName("다음 수집 응답에 없는 리뷰·줄 댓글은 지우지 않고 앞서 저장한 값 그대로 둔다")
    void keepsReviewsAndCommentsMissingFromLaterCollection() {

        Long firstRunId = service.store(request(), detailsOf(List.of(reviewedPullRequest(17,
                review(REVIEW_ID, comment(COMMENT_ID, REVIEW_ID)),
                review(REVIEW_ID + 1, comment(COMMENT_ID + 1, REVIEW_ID + 1)))), List.of()));

        service.store(request(), detailsOf(List.of(reviewedPullRequest(17,
                revisedReview(REVIEW_ID, revisedComment(COMMENT_ID, REVIEW_ID)))), List.of()));

        assertThat(countReviews()).isEqualTo(2);
        assertThat(countComments()).isEqualTo(2);
        assertReviewStored(REVIEW_ID + 1, 17, review(REVIEW_ID + 1), firstRunId);
        assertCommentStored(COMMENT_ID + 1, 17, comment(COMMENT_ID + 1, REVIEW_ID + 1), firstRunId);

    }

    @Test
    @DisplayName("리뷰·줄 댓글은 받은 순서와 상관없이 GitHub id 가 작은 것부터 저장한다")
    void storesReviewsAndCommentsInIdOrder() {

        service.store(request(), detailsOf(List.of(
                reviewedPullRequest(17, review(30, comment(300, 30L)), review(10, comment(100, 10L))),
                reviewedPullRequest(9, review(20, comment(200, 20L)))), List.of()));

        assertThat(jdbc.queryForList("SELECT github_review_id FROM pr_review ORDER BY id", Long.class))
                .containsExactly(10L, 20L, 30L);
        assertThat(jdbc.queryForList("SELECT github_comment_id FROM pr_review_comment ORDER BY id", Long.class))
                .containsExactly(100L, 200L, 300L);

    }

    @Test
    @DisplayName("두 Job 이 같은 리뷰·줄 댓글을 동시에 저장해도 둘 다 성공하고, 행은 하나씩 남아 나중에 저장한 값이 된다")
    void concurrentStoresOfSameReviewAndCommentBothSucceed() throws Exception {

        Long teammateRepositoryId = connectTeammate();
        CollectedReviewComment revised = revisedComment(COMMENT_ID, REVIEW_ID);
        CountDownLatch firstStoredBeforeCommit = new CountDownLatch(1);
        ExecutorService firstJob = Executors.newSingleThreadExecutor();

        try {
            Future<Long> first = firstJob.submit(() -> transaction.execute(status -> {
                Long runId = service.store(request(), detailsOf(
                        List.of(reviewedPullRequest(17, review(REVIEW_ID, comment(COMMENT_ID, REVIEW_ID)))), List.of()));
                firstStoredBeforeCommit.countDown();
                await().atMost(5, TimeUnit.SECONDS).until(this::anotherStoreIsWaiting);
                return runId;
            }));
            firstStoredBeforeCommit.await(5, TimeUnit.SECONDS);

            Long secondRunId = service.store(new IngestRequest(teammateRepositoryId, BRANCHES, List.of()),
                    detailsOf(List.of(reviewedPullRequest(17, revisedReview(REVIEW_ID, revised))), List.of()));

            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(countReviews()).isEqualTo(1);
            assertThat(countComments()).isEqualTo(1);
            assertReviewStored(REVIEW_ID, 17, revisedReview(REVIEW_ID), secondRunId);
            assertThat(storedComment(COMMENT_ID).getBodyExcerpt()).isEqualTo(revised.bodyExcerpt());
            assertThat(storedComment(COMMENT_ID).getCollectionRunId()).isEqualTo(secondRunId);
        } finally {
            firstJob.shutdown();
        }

    }

    @Test
    @DisplayName("연결 저장소가 없으면 아무것도 저장되지 않는다")
    void rejectsUnknownUserRepository() {

        IngestRequest unknown = new IngestRequest(userRepositoryId + 1000, BRANCHES, List.of());

        assertThatThrownBy(() -> service.store(unknown, activityOf(commit(SHA_1))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(runs.count()).isZero();
        assertThat(countCommits()).isZero();
    }

    private IngestRequest request() {
        return new IngestRequest(userRepositoryId, BRANCHES, List.of());
    }

    private Long connectTeammate() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        Long teammateId = fixtures.insertUser(2L, "teammate");
        return fixtures.insertUserRepository(teammateId, repositoryId);

    }

    // 두 번째 저장이 첫 저장의 아직 확정되지 않은 행을 기다리는 중인지 본다 — 그때 첫 저장을 확정해야 두 저장이 반드시 겹친다
    private boolean anotherStoreIsWaiting() {

        return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted",
                Integer.class) > 0;

    }

    private CollectedActivity activityOf(CollectedCommit... collected) {
        return new CollectedActivity(List.of(collected), List.of(), List.of(), null, null);
    }

    private CollectedCommit commit(String sha) {
        return new CollectedCommit(sha, "grow22", "Grow", "feat: 세션 인덱스 추가",
                Instant.parse("2026-09-20T09:00:00Z"), 11, 22, 33, (short) 1, null);
    }

    private int countCommits() {
        return jdbc.queryForObject("SELECT count(*) FROM git_commit", Integer.class);
    }

    private CollectedActivity detailsOf(List<CollectedPullRequest> pullRequests, List<CollectedIssue> issues) {

        return new CollectedActivity(List.of(), pullRequests, issues, null, null);

    }

    private CollectedPullRequest openPullRequest(int number) {

        return new CollectedPullRequest(number, "세션 조회 성능 개선", "인덱스를 추가합니다.", GithubState.OPEN, "grow22",
                "develop", "feature/session-index", PR_OPENED_AT, null, List.of(SHA_1), List.of(42), List.of());

    }

    // 다시 수집했을 때 모든 칸이 바뀌는지 보도록 openPullRequest 와 칸마다 다른 값을 둔다
    private CollectedPullRequest mergedPullRequest(int number) {

        return new CollectedPullRequest(number, "세션 조회 성능 개선 (리뷰 반영)", "인덱스와 캐시를 추가합니다.",
                GithubState.CLOSED, "grow-22", "main", "feature/session-cache", PR_OPENED_AT.plusSeconds(300),
                Instant.parse("2026-09-21T11:00:00Z"), List.of(SHA_1, SHA_2), List.of(42, 43), List.of());

    }

    private CollectedIssue openIssue(int number) {

        return new CollectedIssue(number, "세션 조회가 느립니다", "응답 시간이 오래 걸립니다.", GithubState.OPEN, "reporter",
                List.of("performance"), ISSUE_OPENED_AT, null);

    }

    // 다시 수집했을 때 모든 칸이 바뀌는지 보도록 openIssue 와 칸마다 다른 값을 둔다
    private CollectedIssue closedIssue(int number) {

        return new CollectedIssue(number, "세션 조회가 느립니다 (재현됨)", "인덱스를 추가해 해결했습니다.", GithubState.CLOSED,
                "reporter-2", List.of("performance", "bug"), ISSUE_OPENED_AT.plusSeconds(300),
                Instant.parse("2026-09-21T11:00:00Z"));

    }

    private PullRequest storedPullRequest(int number) {

        return pullRequests.findAll().stream()
                .filter(pr -> pr.getGithubPrNumber() == number)
                .findFirst()
                .orElseThrow();

    }

    private Issue storedIssue(int number) {

        return issues.findAll().stream()
                .filter(issue -> issue.getGithubIssueNumber() == number)
                .findFirst()
                .orElseThrow();

    }

    private void assertPullRequestStored(int number, CollectedPullRequest expected, Long runId) {

        PullRequest stored = storedPullRequest(number);
        assertThat(stored.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(stored.getCollectionRunId()).isEqualTo(runId);
        assertThat(stored.getTitle()).isEqualTo(expected.title());
        assertThat(stored.getBodyExcerpt()).isEqualTo(expected.bodyExcerpt());
        assertThat(stored.getState()).isEqualTo(expected.state());
        assertThat(stored.getAuthorLogin()).isEqualTo(expected.authorLogin());
        assertThat(stored.getBaseBranch()).isEqualTo(expected.baseBranch());
        assertThat(stored.getHeadBranch()).isEqualTo(expected.headBranch());
        assertThat(stored.getOpenedAt()).isEqualTo(expected.openedAt());
        assertThat(stored.getMergedAt()).isEqualTo(expected.mergedAt());
        assertThat(stored.getCommitShas()).containsExactlyElementsOf(expected.commitShas());
        assertThat(stored.getLinkedIssueNumbers()).containsExactlyElementsOf(expected.linkedIssueNumbers());

    }

    private void assertIssueStored(int number, CollectedIssue expected, Long runId) {

        Issue stored = storedIssue(number);
        assertThat(stored.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(stored.getCollectionRunId()).isEqualTo(runId);
        assertThat(stored.getTitle()).isEqualTo(expected.title());
        assertThat(stored.getBodyExcerpt()).isEqualTo(expected.bodyExcerpt());
        assertThat(stored.getState()).isEqualTo(expected.state());
        assertThat(stored.getAuthorLogin()).isEqualTo(expected.authorLogin());
        assertThat(stored.getLabels()).containsExactlyElementsOf(expected.labels());
        assertThat(stored.getOpenedAt()).isEqualTo(expected.openedAt());
        assertThat(stored.getClosedAt()).isEqualTo(expected.closedAt());

    }

    private int countPullRequests() {

        return jdbc.queryForObject("SELECT count(*) FROM pull_request", Integer.class);

    }

    private int countIssues() {

        return jdbc.queryForObject("SELECT count(*) FROM issue", Integer.class);

    }

    // setUp 이 만든 다른 저장소(other)를 같은 사용자에게 연결한다
    private Long connectOtherRepository() {

        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE github_login = 'grow22'", Long.class);
        Long otherRepositoryId = jdbc.queryForObject("SELECT id FROM repository WHERE name = 'other'", Long.class);
        return new TestFixtures(jdbc).insertUserRepository(userId, otherRepositoryId);

    }

    private CollectedPullRequest reviewedPullRequest(int number, CollectedReview... reviewed) {

        return new CollectedPullRequest(number, "세션 조회 성능 개선", "인덱스를 추가합니다.", GithubState.OPEN, "grow22",
                "develop", "feature/session-index", PR_OPENED_AT, null, List.of(SHA_1), List.of(42), List.of(reviewed));

    }

    private CollectedReview review(long reviewId, CollectedReviewComment... comments) {

        return new CollectedReview(reviewId, ReviewState.CHANGES_REQUESTED, "reviewer", "책임 분리를 명확히 해주세요.",
                REVIEWED_AT, List.of(comments));

    }

    // 다시 수집했을 때 바뀌어야 할 칸이 모두 바뀌는지 보도록 review 와 칸마다 다른 값을 둔다
    private CollectedReview revisedReview(long reviewId, CollectedReviewComment... comments) {

        return new CollectedReview(reviewId, ReviewState.DISMISSED, "reviewer-2", "책임 분리 반영을 확인했습니다.",
                REVIEWED_AT.plusSeconds(600), List.of(comments));

    }

    private CollectedReviewComment comment(long commentId, Long reviewId) {

        return new CollectedReviewComment(commentId, reviewId, "reviewer", "인덱스 책임을 repository 계층으로 옮겨주세요.",
                "backend/session.sql", 12, COMMENTED_AT);

    }

    // 다시 수집했을 때 바뀌어야 할 칸은 모두 바뀌고 작성 시각은 남는지 보도록 comment 와 칸마다 다른 값을 둔다
    private CollectedReviewComment revisedComment(long commentId, Long reviewId) {

        return new CollectedReviewComment(commentId, reviewId, "reviewer-2", "계층 이동을 확인했습니다.",
                "backend/session_index.sql", 30, COMMENTED_AT.plusSeconds(600));

    }

    // 줄 번호만 다르게 같은 댓글을 다시 수집한 것처럼 만든다
    private CollectedReviewComment commentOnLine(Integer line) {

        return new CollectedReviewComment(COMMENT_ID, REVIEW_ID, "reviewer", "인덱스 책임을 repository 계층으로 옮겨주세요.",
                "backend/session.sql", line, COMMENTED_AT);

    }

    private PrReview storedReview(long githubReviewId) {

        return reviews.findAll().stream()
                .filter(review -> review.getGithubReviewId() == githubReviewId)
                .findFirst()
                .orElseThrow();

    }

    private PrReviewComment storedComment(long githubCommentId) {

        return reviewComments.findAll().stream()
                .filter(comment -> comment.getGithubCommentId() == githubCommentId)
                .findFirst()
                .orElseThrow();

    }

    private void assertReviewStored(long githubReviewId, int prNumber, CollectedReview expected, Long runId) {

        PrReview stored = storedReview(githubReviewId);
        assertThat(stored.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(stored.getGithubPrNumber()).isEqualTo(prNumber);
        assertThat(stored.getCollectionRunId()).isEqualTo(runId);
        assertThat(stored.getState()).isEqualTo(expected.state());
        assertThat(stored.getAuthorLogin()).isEqualTo(expected.authorLogin());
        assertThat(stored.getBodyExcerpt()).isEqualTo(expected.bodyExcerpt());
        assertThat(stored.getSubmittedAt()).isEqualTo(expected.submittedAt());

    }

    private void assertCommentStored(long githubCommentId, int prNumber, CollectedReviewComment expected, Long runId) {

        PrReviewComment stored = storedComment(githubCommentId);
        assertThat(stored.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(stored.getGithubPrNumber()).isEqualTo(prNumber);
        assertThat(stored.getCollectionRunId()).isEqualTo(runId);
        assertThat(stored.getGithubReviewId()).isEqualTo(expected.reviewId());
        assertThat(stored.getAuthorLogin()).isEqualTo(expected.authorLogin());
        assertThat(stored.getBodyExcerpt()).isEqualTo(expected.bodyExcerpt());
        assertThat(stored.getPath()).isEqualTo(expected.path());
        assertThat(stored.getLine()).isEqualTo(expected.line());
        assertThat(stored.getCommentedAt()).isEqualTo(expected.commentedAt());

    }

    private int countReviews() {

        return jdbc.queryForObject("SELECT count(*) FROM pr_review", Integer.class);

    }

    private int countComments() {

        return jdbc.queryForObject("SELECT count(*) FROM pr_review_comment", Integer.class);

    }
}
