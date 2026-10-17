package com.gitory.backend.recommend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.recommend.infra.CandidateCommitRepository;
import com.gitory.backend.recommend.infra.CandidateGroupingRepository;
import com.gitory.backend.recommend.infra.CandidateRepository;
import com.gitory.backend.support.CandidateFixtures;
import com.gitory.backend.support.StoredActivityFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@DataJpaTest(properties = "gitory.analysis.grouping.version=" + CandidateStoreServiceTest.VERSION)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({CandidateStoreService.class, StoredActivityService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CandidateStoreServiceTest {

    // application.yml 의 값과 다른 묶기 버전을 걸어 설정값을 읽는지 본다
    static final int VERSION = 7;

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String SHA_C = "c".repeat(40);
    private static final String SHA_D = "d".repeat(40);
    private static final String SHA_E = "e".repeat(40);
    private static final String OTHER_REPOSITORY_SHA = "9".repeat(40);
    private static final String FINGERPRINT = "f".repeat(64);
    private static final String OTHER_FINGERPRINT = "0".repeat(64);
    private static final Instant SEP_1 = Instant.parse("2026-09-01T01:00:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CandidateStoreService service;

    @Autowired
    CandidateRepository candidates;

    @Autowired
    CandidateCommitRepository candidateCommits;

    @Autowired
    CandidateGroupingRepository groupings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    private CandidateFixtures candidateRows;
    private StoredActivityFixtures activity;
    private ExecutorService otherRequest;
    private Long repositoryId;
    private Long otherRepositoryId;
    private Long userRepositoryId;
    private Long teammateRepositoryId;

    @BeforeEach
    void setUp() {

        TestFixtures fixtures = new TestFixtures(jdbc);
        fixtures.clear();
        candidateRows = new CandidateFixtures(jdbc);
        activity = new StoredActivityFixtures(jdbc);
        otherRequest = Executors.newSingleThreadExecutor();
        Long userId = fixtures.insertUser(1L, "grow22");
        Long teammateId = fixtures.insertUser(2L, "teammate");
        // 저장소를 먼저 하나 더 만들어 user_repository.id 와 repository.id 를 다르게 한다
        otherRepositoryId = fixtures.insertRepository(1L, "grow22", "other");
        repositoryId = fixtures.insertRepository(2L, "grow22", "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        teammateRepositoryId = fixtures.insertUserRepository(teammateId, repositoryId);

    }

    @AfterEach
    void tearDown() {

        otherRequest.shutdownNow();

    }

    @Test
    @DisplayName("처음 묶으면 AI 가 준 후보를 받은 순서대로 NEW 후보로 저장하고 ref·대표 PR·PR 번호 목록·이슈 번호 목록·제목·이유·점수·카드감 낮음을 받은 값 그대로 담는다")
    void storesGroupsInGivenOrderAsNewCandidates() {

        commit(SHA_A);
        commit(SHA_B);
        commit(SHA_C);
        GroupedCandidate pullRequest = new GroupedCandidate(CandidateType.PR, "#42", 42, List.of(42, 45), List.of(7),
                "로그인 세션 처리", "리뷰 코멘트가 많았던 작업", 0.6123, false, List.of(SHA_A, SHA_B));
        GroupedCandidate cluster = new GroupedCandidate(CandidateType.COMMIT_CLUSTER, "2026-09-23", null, List.of(),
                List.of(31), "결제 모듈 정리", "같은 날 이어진 작업", 0.3, true, List.of(SHA_C));

        service.store(userRepositoryId, FINGERPRINT, List.of(pullRequest, cluster));

        List<Candidate> stored = storedCandidates();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0)).satisfies(candidate -> {
            assertThat(candidate.getSourceType()).isEqualTo(CandidateType.PR);
            assertThat(candidate.getSourceRef()).isEqualTo("#42");
            assertThat(candidate.getGithubPrNumber()).isEqualTo(42);
            assertThat(candidate.getPullRequestNumbers()).containsExactly(42, 45);
            assertThat(candidate.getIssueNumbers()).containsExactly(7);
            assertThat(candidate.getTitle()).isEqualTo("로그인 세션 처리");
            assertThat(candidate.getReasonText()).isEqualTo("리뷰 코멘트가 많았던 작업");
            assertThat(candidate.getScore()).isEqualByComparingTo("0.6123");
            assertThat(candidate.isLowCardWorth()).isFalse();
            assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);
            assertThat(candidate.isUserModified()).isFalse();
        });
        assertThat(stored.get(1)).satisfies(candidate -> {
            assertThat(candidate.getSourceType()).isEqualTo(CandidateType.COMMIT_CLUSTER);
            assertThat(candidate.getSourceRef()).isEqualTo("2026-09-23");
            assertThat(candidate.getGithubPrNumber()).isNull();
            assertThat(candidate.getPullRequestNumbers()).isEmpty();
            assertThat(candidate.getIssueNumbers()).containsExactly(31);
            assertThat(candidate.getTitle()).isEqualTo("결제 모듈 정리");
            assertThat(candidate.getReasonText()).isEqualTo("같은 날 이어진 작업");
            assertThat(candidate.getScore()).isEqualByComparingTo("0.3");
            assertThat(candidate.isLowCardWorth()).isTrue();
            assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);
            assertThat(candidate.isUserModified()).isFalse();
        });

    }

    @Test
    @DisplayName("후보 커밋은 AI 가 준 커밋마다 AI 출처·포함된 상태로 받은 순서대로 잇는다")
    void linksGivenCommitsInOrder() {

        Long first = commit(SHA_A);
        Long second = commit(SHA_B);
        Long third = commit(SHA_C);

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-01", 0.4, SHA_C, SHA_A, SHA_B)));

        List<CandidateCommit> links = linksOf(storedCandidates().getFirst().getId());
        assertThat(links).extracting(CandidateCommit::getCommitId).containsExactly(third, first, second);
        assertThat(links).allSatisfy(link -> {
            assertThat(link.getOrigin()).isEqualTo(CandidateCommitOrigin.AI);
            assertThat(link.isIncluded()).isTrue();
        });

    }

    @Test
    @DisplayName("다시 묶으면 손대지 않은 NEW 후보와 그 커밋 연결을 지우고 새 결과로 바꾼다")
    void replacesUntouchedCandidatesOnRegrouping() {

        Long first = commit(SHA_A);
        Long second = commit(SHA_B);
        service.store(userRepositoryId, FINGERPRINT,
                List.of(pullRequest(42, List.of(42), 0.5, SHA_A), cluster("2026-09-01", 0.4, SHA_B)));
        List<Long> before = ids(storedCandidates());

        service.store(userRepositoryId, OTHER_FINGERPRINT, List.of(cluster("2026-09-01", 0.6, SHA_A, SHA_B)));

        List<Candidate> after = storedCandidates();
        assertThat(after).singleElement().satisfies(candidate -> {
            assertThat(candidate.getId()).isNotIn(before);
            assertThat(commitIdsOf(candidate.getId())).containsExactly(first, second);
        });
        assertThat(candidateCommits.findAll()).hasSize(2);

    }

    @Test
    @DisplayName("손댄 후보(USED·EXCLUDED·손댄 표시가 켜진 NEW·직접 추가)는 다시 묶어도 지우지 않고 칸과 커밋 연결을 그대로 둔다")
    void keepsTouchedCandidatesAndTheirCommits() {

        Long used = candidate(userRepositoryId, "PR", 42, "USED", false, "{42}", commit(SHA_A));
        Long excluded = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED", false, "{}", commit(SHA_B));
        Long modified = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", true, "{}", commit(SHA_C));
        Long manual = candidate(userRepositoryId, "MANUAL", null, "NEW", false, "{}", commit(SHA_D));
        Long freed = commit(SHA_E);
        Long untouched = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}", freed);

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-05", 0.4, SHA_E)));

        List<Candidate> stored = storedCandidates();
        assertThat(ids(stored)).contains(used, excluded, modified, manual).doesNotContain(untouched).hasSize(5);
        assertThat(stored).filteredOn(candidate -> List.of(used, excluded, modified, manual).contains(candidate.getId()))
                .extracting(Candidate::getStatus, Candidate::isUserModified, Candidate::getTitle)
                .containsExactly(
                        tuple(CandidateStatus.USED, false, "후보"),
                        tuple(CandidateStatus.EXCLUDED, false, "후보"),
                        tuple(CandidateStatus.NEW, true, "후보"),
                        tuple(CandidateStatus.NEW, false, "후보"));
        assertThat(commitIdsOf(used)).hasSize(1);
        assertThat(commitIdsOf(excluded)).hasSize(1);
        assertThat(commitIdsOf(modified)).hasSize(1);
        assertThat(commitIdsOf(manual)).hasSize(1);
        assertThat(stored.getLast().getSourceRef()).isEqualTo("2026-09-05");
        assertThat(commitIdsOf(stored.getLast().getId())).containsExactly(freed);

    }

    @Test
    @DisplayName("새 묶음의 커밋이 손댄 후보에 포함된 커밋과 하나라도 겹치면 그 묶음은 저장하지 않는다")
    void dropsGroupOverlappingTouchedCommits() {

        Long excluded = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED", false, "{}", commit(SHA_A));
        commit(SHA_B);
        Long kept = commit(SHA_C);

        service.store(userRepositoryId, FINGERPRINT,
                List.of(cluster("2026-09-01", 0.5, SHA_B, SHA_A), cluster("2026-09-03", 0.4, SHA_C)));

        assertThat(storedCandidates()).filteredOn(candidate -> !candidate.getId().equals(excluded))
                .singleElement()
                .satisfies(candidate -> assertThat(commitIdsOf(candidate.getId())).containsExactly(kept));

    }

    @ParameterizedTest
    @CsvSource({"COMMIT_CLUSTER, true", "MANUAL, false"})
    @DisplayName("손댄 표시가 켜진 NEW 후보와 직접 추가한 후보도 손댄 후보라, 그 후보에 포함된 커밋과 겹치는 묶음은 저장하지 않는다")
    void dropsGroupOverlappingModifiedOrManualCommits(String type, boolean userModified) {

        Long touched = candidate(userRepositoryId, type, null, "NEW", userModified, "{}", commit(SHA_A));
        commit(SHA_B);
        Long kept = commit(SHA_C);

        service.store(userRepositoryId, FINGERPRINT,
                List.of(cluster("2026-09-01", 0.5, SHA_B, SHA_A), cluster("2026-09-03", 0.4, SHA_C)));

        assertThat(storedCandidates()).filteredOn(candidate -> !candidate.getId().equals(touched))
                .singleElement()
                .satisfies(candidate -> assertThat(commitIdsOf(candidate.getId())).containsExactly(kept));

    }

    @Test
    @DisplayName("손댄 후보에서 사용자가 뺀 커밋과 겹치는 묶음은 저장한다")
    void keepsGroupOverlappingCommitUserTookOut() {

        Long excluded = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED", false, "{}", commit(SHA_A));
        Long takenOut = commit(SHA_B);
        linkTakenOut(excluded, takenOut);

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-02", 0.5, SHA_B)));

        assertThat(storedCandidates()).filteredOn(candidate -> !candidate.getId().equals(excluded))
                .singleElement()
                .satisfies(candidate -> assertThat(commitIdsOf(candidate.getId())).containsExactly(takenOut));

    }

    @Test
    @DisplayName("같은 저장소라도 다른 사용자 연결의 후보는 지우지 않고, 그 후보가 손댄 커밋도 이 연결의 묶음을 막지 않는다")
    void leavesOtherConnectionsCandidates() {

        Long shared = commit(SHA_A);
        Long teammateTouched = candidate(teammateRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED", false, "{}", shared);
        Long teammateUntouched = candidate(teammateRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}");

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-01", 0.5, SHA_A)));

        assertThat(ids(candidates.findByUserRepositoryId(teammateRepositoryId)))
                .containsExactlyInAnyOrder(teammateTouched, teammateUntouched);
        assertThat(storedCandidates()).singleElement()
                .satisfies(candidate -> assertThat(commitIdsOf(candidate.getId())).containsExactly(shared));

    }

    @Test
    @DisplayName("후보 숫자로 바뀐 파일 수·후보에 든 모든 PR 의 줄 댓글 수·기간·코드 비율을 저장된 커밋과 댓글에서 계산해 저장한다")
    void storesMetaFromStoredActivity() {

        Long first = activity.insertCommit(repositoryId, SHA_A, SEP_1, 2);
        activity.insertFile(first, "src/Session.java", 30, 10);
        activity.insertFile(first, "README.md", 10, 0);
        Long second = activity.insertCommit(repositoryId, SHA_B,
                SEP_1.plus(Duration.ofDays(21)).minus(Duration.ofHours(1)), 2);
        activity.insertFile(second, "src/Session.java", 5, 5);
        activity.insertFile(second, "src/Token.java", 20, 0);
        activity.insertPullRequest(repositoryId, 42);
        activity.insertPullRequest(repositoryId, 45);
        activity.insertPullRequest(repositoryId, 99);
        activity.insertPullRequest(otherRepositoryId, 42);
        activity.insertReviewComment(repositoryId, 42, 1L);
        activity.insertReviewComment(repositoryId, 42, 2L);
        activity.insertReviewComment(repositoryId, 45, 3L);
        activity.insertReviewComment(repositoryId, 99, 4L);
        activity.insertReviewComment(otherRepositoryId, 42, 5L);

        service.store(userRepositoryId, FINGERPRINT, List.of(pullRequest(42, List.of(42, 45), 0.6, SHA_A, SHA_B)));

        assertThat(storedCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.getFileCount()).isEqualTo(3);
            assertThat(candidate.getReviewCommentCount()).isEqualTo(3);
            assertThat(candidate.getSpanDays()).isEqualTo(20);
            assertThat(candidate.getCodeRatio()).isEqualByComparingTo("0.875");
        });

    }

    @Test
    @DisplayName("후보 커밋마다 그 커밋에서 바뀐 줄이 가장 많은 파일을 표시 경로로 저장하고, 바뀐 파일 정보가 없으면 빈 글자로 저장한다")
    void storesDisplayPathPerCommit() {

        Long first = activity.insertCommit(repositoryId, SHA_A, SEP_1, 2);
        activity.insertFile(first, "src/Small.java", 1, 1);
        activity.insertFile(first, "src/Big.java", 50, 0);
        activity.insertCommit(repositoryId, SHA_B, SEP_1, 4);

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-01", 0.4, SHA_A, SHA_B)));

        assertThat(linksOf(storedCandidates().getFirst().getId()))
                .extracting(CandidateCommit::getDisplayPath)
                .containsExactly("src/Big.java", "");

    }

    @Test
    @DisplayName("저장하면 묶은 기록에 지문·설정한 묶기 버전·묶은 시각을 남기고, 다시 저장하면 그 기록을 이번 값으로 바꾼다")
    void recordsGroupingWithConfiguredVersion() {

        commit(SHA_A);
        commit(SHA_B);
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-01", 0.4, SHA_A)));

        CandidateGrouping first = groupings.findById(userRepositoryId).orElseThrow();
        assertThat(first.getInputFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(first.getGroupingVersion()).isEqualTo(VERSION);
        assertThat(first.getGroupedAt()).isAfterOrEqualTo(before);

        service.store(userRepositoryId, OTHER_FINGERPRINT, List.of(cluster("2026-09-01", 0.4, SHA_B)));

        CandidateGrouping second = groupings.findById(userRepositoryId).orElseThrow();
        assertThat(second.getInputFingerprint()).isEqualTo(OTHER_FINGERPRINT);
        assertThat(second.getGroupingVersion()).isEqualTo(VERSION);
        assertThat(second.getGroupedAt()).isAfter(first.getGroupedAt());
        assertThat(groupings.count()).isOne();

    }

    @Test
    @DisplayName("이 저장소에 저장되지 않은 커밋이 든 묶음이 오면 후보와 묶은 기록을 하나도 바꾸지 않고 실패한다")
    void failsWithoutChangesOnUnstoredCommit() {

        commit(SHA_A);
        commit(SHA_B);
        activity.insertCommit(otherRepositoryId, OTHER_REPOSITORY_SHA, SEP_1, 1);
        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-01", 0.4, SHA_A)));
        List<Long> before = ids(storedCandidates());

        assertThatThrownBy(() -> service.store(userRepositoryId, OTHER_FINGERPRINT, List.of(
                cluster("2026-09-02", 0.5, SHA_B), cluster("2026-09-03", 0.3, OTHER_REPOSITORY_SHA))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OTHER_REPOSITORY_SHA);

        assertThat(ids(storedCandidates())).isEqualTo(before);
        assertThat(groupings.findById(userRepositoryId).orElseThrow().getInputFingerprint()).isEqualTo(FINGERPRINT);

    }

    @Test
    @DisplayName("저장하다 실패하면 지운 후보와 먼저 저장한 후보를 모두 되돌려 저장 전 그대로 둔다")
    void rollsBackEverythingWhenSavingFails() {

        Long untouched = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}", commit(SHA_A));
        commit(SHA_B);
        commit(SHA_C);
        GroupedCandidate tooLongTitle = new GroupedCandidate(CandidateType.COMMIT_CLUSTER, "2026-09-03", null,
                List.of(), List.of(), "가".repeat(201), "같은 날 이어진 작업", 0.3, false, List.of(SHA_C));

        assertThatThrownBy(() -> service.store(userRepositoryId, FINGERPRINT,
                List.of(cluster("2026-09-02", 0.5, SHA_B), tooLongTitle)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(ids(storedCandidates())).containsExactly(untouched);
        assertThat(commitIdsOf(untouched)).hasSize(1);
        assertThat(groupings.count()).isZero();

    }

    @Test
    @DisplayName("AI 가 후보를 하나도 주지 않으면 손대지 않은 후보를 모두 지우고 손댄 후보와 묶은 기록만 남긴다")
    void emptyResultRemovesUntouchedCandidates() {

        candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}", commit(SHA_A));
        Long excluded = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED", false, "{}", commit(SHA_B));

        service.store(userRepositoryId, FINGERPRINT, List.of());

        assertThat(ids(storedCandidates())).containsExactly(excluded);
        assertThat(groupings.findById(userRepositoryId)).isPresent();

    }

    @Test
    @DisplayName("묶기에 보내지 않을 것으로 손댄 후보에 포함된 커밋의 sha 와 손댄 후보에 든 모든 PR 번호를 돌려준다")
    void lockedSourcesHoldTouchedCommitsAndPullRequests() {

        candidate(userRepositoryId, "PR", 42, "USED", false, "{42,45}", commit(SHA_A));
        candidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED", false, "{}", commit(SHA_B));
        candidate(userRepositoryId, "PR", 50, "NEW", true, "{50}", commit(SHA_C));
        candidate(userRepositoryId, "MANUAL", null, "NEW", false, "{}", commit(SHA_D));

        LockedSources locked = service.lockedSources(userRepositoryId);

        assertThat(locked.commitShas()).containsExactlyInAnyOrder(SHA_A, SHA_B, SHA_C, SHA_D);
        assertThat(locked.pullRequestNumbers()).containsExactlyInAnyOrder(42, 45, 50);

    }

    @Test
    @DisplayName("손대지 않은 NEW 후보의 커밋·PR, 손댄 후보에서 사용자가 뺀 커밋, 다른 사용자 연결의 후보는 묶기에서 빼지 않는다")
    void lockedSourcesSkipUntouchedAndTakenOut() {

        candidate(userRepositoryId, "PR", 60, "NEW", false, "{60}", commit(SHA_A));
        Long excluded = candidate(userRepositoryId, "PR", 61, "EXCLUDED", false, "{61}", commit(SHA_B));
        linkTakenOut(excluded, commit(SHA_C));
        candidate(teammateRepositoryId, "PR", 70, "EXCLUDED", false, "{70}", commit(SHA_D));

        LockedSources locked = service.lockedSources(userRepositoryId);

        assertThat(locked.commitShas()).containsExactly(SHA_B);
        assertThat(locked.pullRequestNumbers()).containsExactly(61);

    }

    @Test
    @DisplayName("회상 도우미로 만든 후보는 커밋이 없어도 손댄 후보라 다시 묶어도 지우거나 바꾸지 않고, 묶기에서 뺄 커밋과 PR 도 늘리지 않는다")
    void keepsRecallCandidateWithoutLockingAnything() {

        Long recall = candidates.save(Candidate.manual(userRepositoryId, "RECALL", "/main/java/ 작업", "로그인을 맡았다", CandidateMeta.of(List.of()))).getId();
        commit(SHA_A);

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-05", 0.4, SHA_A)));

        assertThat(ids(storedCandidates())).contains(recall).hasSize(2);
        assertThat(candidates.findById(recall).orElseThrow()).satisfies(candidate -> {
            assertThat(candidate.getTitle()).isEqualTo("/main/java/ 작업");
            assertThat(candidate.getReasonText()).isEqualTo("로그인을 맡았다");
            assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.NEW);
            assertThat(commitIdsOf(candidate.getId())).isEmpty();
        });
        LockedSources locked = service.lockedSources(userRepositoryId);
        assertThat(locked.commitShas()).isEmpty();
        assertThat(locked.pullRequestNumbers()).isEmpty();

    }

    @Test
    @DisplayName("직접 추가해 USER 출처로 붙인 커밋은 다시 경험 그룹화를 해도 후보를 그대로 두고, 그 커밋이 든 묶음만 저장하지 않으며 경험 그룹화에서 뺄 커밋에 들고 PR 은 막지 않는다")
    void keepsAttachedCommitsOfManualCandidateAndLocksOnlyThoseCommits() {

        Long attached = commit(SHA_A);
        commit(SHA_B);
        commit(SHA_C);
        Long manual = candidates.save(Candidate.manual(userRepositoryId, "MANUAL", "직접 추가", "요약",
                CandidateMeta.of(List.of()))).getId();
        candidateCommits.save(CandidateCommit.attached(manual, attached, "src/A.java"));

        service.store(userRepositoryId, FINGERPRINT,
                List.of(cluster("2026-09-01", 0.5, SHA_A, SHA_B), cluster("2026-09-02", 0.4, SHA_C)));

        assertThat(storedCandidates()).hasSize(2);
        assertThat(storedCandidates().getFirst().getId()).isEqualTo(manual);
        assertThat(storedCandidates().getLast().getSourceRef()).isEqualTo("2026-09-02");
        assertThat(linksOf(manual)).singleElement().satisfies(link -> {
            assertThat(link.getOrigin()).isEqualTo(CandidateCommitOrigin.USER);
            assertThat(link.getCommitId()).isEqualTo(attached);
        });
        LockedSources locked = service.lockedSources(userRepositoryId);
        assertThat(locked.commitShas()).containsExactly(SHA_A);
        assertThat(locked.pullRequestNumbers()).isEmpty();

    }

    @Test
    @DisplayName("손댄 후보가 없으면 묶기에서 뺄 커밋과 PR 이 없다")
    void lockedSourcesAreEmptyWithoutTouchedCandidates() {

        candidate(userRepositoryId, "PR", 60, "NEW", false, "{60}", commit(SHA_A));

        LockedSources locked = service.lockedSources(userRepositoryId);

        assertThat(locked.commitShas()).isEmpty();
        assertThat(locked.pullRequestNumbers()).isEmpty();

    }

    @Test
    @DisplayName("지난번 묶었을 때와 지문·묶기 버전이 같으면 true 이고 후보는 그대로 둔 채 묶은 시각만 지금으로 바꾼다")
    void keepsLastCandidatesWhenInputIsSame() {

        Long kept = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}", commit(SHA_A));
        insertGrouping(FINGERPRINT, VERSION, LONG_AGO);

        assertThat(service.keepIfSameInput(userRepositoryId, FINGERPRINT)).isTrue();

        CandidateGrouping grouping = groupings.findById(userRepositoryId).orElseThrow();
        assertThat(grouping.getInputFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(grouping.getGroupingVersion()).isEqualTo(VERSION);
        assertThat(grouping.getGroupedAt()).isAfter(LONG_AGO);
        assertThat(ids(storedCandidates())).containsExactly(kept);

    }

    @ParameterizedTest
    @CsvSource({"true, " + (VERSION - 1), "false, " + VERSION})
    @DisplayName("지난번 묶었을 때와 지문이나 묶기 버전이 다르면 false 이고 묶은 기록을 바꾸지 않는다")
    void regroupsWhenInputOrVersionDiffers(boolean sameFingerprint, int storedVersion) {

        String stored = sameFingerprint ? FINGERPRINT : OTHER_FINGERPRINT;
        insertGrouping(stored, storedVersion, LONG_AGO);

        assertThat(service.keepIfSameInput(userRepositoryId, FINGERPRINT)).isFalse();

        CandidateGrouping grouping = groupings.findById(userRepositoryId).orElseThrow();
        assertThat(grouping.getInputFingerprint()).isEqualTo(stored);
        assertThat(grouping.getGroupingVersion()).isEqualTo(storedVersion);
        assertThat(grouping.getGroupedAt()).isEqualTo(LONG_AGO);

    }

    @Test
    @DisplayName("묶은 기록이 없으면 false 이고 기록을 만들지 않는다")
    void regroupsWhenNeverGrouped() {

        assertThat(service.keepIfSameInput(userRepositoryId, FINGERPRINT)).isFalse();

        assertThat(groupings.count()).isZero();

    }

    @Test
    @DisplayName("지문은 보낼 요청과 설정한 묶기 버전으로 만든다")
    void fingerprintUsesConfiguredVersion() {

        String request = "{\"repository_id\":\"1\",\"commits\":[]}";

        assertThat(service.fingerprint(request))
                .isEqualTo(CandidateGrouping.fingerprintOf(request, VERSION))
                .isNotEqualTo(CandidateGrouping.fingerprintOf(request, 1));

    }

    @Test
    @DisplayName("후보 수는 상태와 상관없이 이 연결의 후보를 모두 센다")
    void countsCandidatesOfEveryStatus() {

        candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}");
        candidate(userRepositoryId, "PR", 42, "USED", false, "{42}");
        candidate(userRepositoryId, "COMMIT_CLUSTER", null, "EXCLUDED", false, "{}");
        candidate(userRepositoryId, "MANUAL", null, "NEW", false, "{}");
        candidate(teammateRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}");

        assertThat(service.countCandidates(userRepositoryId)).isEqualTo(4);

    }

    @Test
    @DisplayName("보드 순서는 점수가 높은 후보부터이고, 점수가 같으면 먼저 저장한 후보, 점수가 없는 후보는 맨 뒤다")
    void boardOrderIsScoreThenInsertion() {

        candidate(userRepositoryId, "MANUAL", null, "NEW", false, "{}");
        commit(SHA_A);
        commit(SHA_B);
        commit(SHA_C);
        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-01", 0.5, SHA_A),
                cluster("2026-09-02", 0.9, SHA_B), cluster("2026-09-03", 0.5, SHA_C)));
        List<Candidate> loaded = new ArrayList<>(storedCandidates());
        Collections.reverse(loaded);

        loaded.sort(Candidate.BOARD_ORDER);

        assertThat(loaded).extracting(Candidate::getTitle)
                .containsExactly("2026-09-02 작업", "2026-09-01 작업", "2026-09-03 작업", "후보");

    }

    @Test
    @DisplayName("저장은 이 연결의 후보를 잠근 뒤 손댄 후보를 가려, 저장이 기다리는 사이 다른 요청이 손댄 후보는 지우지 않고 그 커밋이 든 묶음은 버린다")
    void storeWaitsForCandidateTouchedMeanwhile() throws Exception {

        Long touched = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}", commit(SHA_A));
        Long free = commit(SHA_B);
        CountDownLatch usedBeforeCommit = new CountDownLatch(1);
        Future<?> cardRequest = otherRequest.submit(() -> transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM candidate WHERE id = ? FOR UPDATE", Long.class, touched);
            jdbc.update("UPDATE candidate SET status = 'USED' WHERE id = ?", touched);
            usedBeforeCommit.countDown();
            await().atMost(5, TimeUnit.SECONDS).until(this::anotherRequestIsWaiting);
        }));
        assertThat(usedBeforeCommit.await(5, TimeUnit.SECONDS)).isTrue();

        service.store(userRepositoryId, FINGERPRINT,
                List.of(cluster("2026-09-01", 0.5, SHA_A), cluster("2026-09-02", 0.4, SHA_B)));
        cardRequest.get(5, TimeUnit.SECONDS);

        List<Candidate> stored = storedCandidates();
        assertThat(stored).filteredOn(candidate -> candidate.getId().equals(touched))
                .singleElement()
                .extracting(Candidate::getStatus)
                .isEqualTo(CandidateStatus.USED);
        assertThat(stored).filteredOn(candidate -> !candidate.getId().equals(touched))
                .singleElement()
                .satisfies(candidate -> assertThat(commitIdsOf(candidate.getId())).containsExactly(free));

    }

    @Test
    @DisplayName("저장은 후보를 id 순으로 잠가, 후보를 id 순으로 잠그는 다른 요청과 서로 상대를 기다리며 멈추지 않는다")
    void storeLocksCandidatesInIdOrder() throws Exception {

        Long low = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}", commit(SHA_A));
        Long high = candidate(userRepositoryId, "COMMIT_CLUSTER", null, "NEW", false, "{}", commit(SHA_B));
        // 고친 행은 표 뒤에 새로 쓰이므로 높은 id 를 먼저 고쳐, 표에 놓인 순서와 점수 순서가 모두 id 와 거꾸로 되게 한다
        jdbc.update("UPDATE candidate SET score = 0.9 WHERE id = ?", high);
        jdbc.update("UPDATE candidate SET score = 0.1 WHERE id = ?", low);
        CountDownLatch lowLocked = new CountDownLatch(1);
        Future<?> cardRequest = otherRequest.submit(() -> transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM candidate WHERE id = ? FOR UPDATE", Long.class, low);
            lowLocked.countDown();
            await().atMost(5, TimeUnit.SECONDS).until(this::anotherRequestIsWaiting);
            jdbc.queryForObject("SELECT id FROM candidate WHERE id = ? FOR UPDATE", Long.class, high);
        }));
        assertThat(lowLocked.await(5, TimeUnit.SECONDS)).isTrue();

        service.store(userRepositoryId, FINGERPRINT, List.of(cluster("2026-09-03", 0.5, SHA_A, SHA_B)));
        cardRequest.get(5, TimeUnit.SECONDS);

        assertThat(storedCandidates()).singleElement()
                .satisfies(candidate -> assertThat(commitIdsOf(candidate.getId())).hasSize(2));

    }

    private Long commit(String sha) {

        return activity.insertCommit(repositoryId, sha, SEP_1, 1);

    }

    private static GroupedCandidate cluster(String ref, double score, String... shas) {

        return new GroupedCandidate(CandidateType.COMMIT_CLUSTER, ref, null, List.of(), List.of(), ref + " 작업",
                "같은 날 이어진 작업", score, false, List.of(shas));

    }

    private static GroupedCandidate pullRequest(int number, List<Integer> numbers, double score, String... shas) {

        return new GroupedCandidate(CandidateType.PR, "#" + number, number, numbers, List.of(), "PR #" + number + " 작업",
                "리뷰가 남은 작업", score, false, List.of(shas));

    }

    /** 사용자가 손댄 후보처럼 상태·손댄 표시·PR 번호 목록을 정해 넣고, 받은 커밋을 포함된 상태로 잇는다 */
    private Long candidate(Long connectionId, String type, Integer prNumber, String status, boolean userModified,
                           String pullRequestNumbers, Long... commitIds) {

        Long id = candidateRows.insertCandidate(connectionId, type, prNumber, status);
        jdbc.update("UPDATE candidate SET is_user_modified = ?, pull_request_numbers = ?::int[] WHERE id = ?",
                userModified, pullRequestNumbers, id);
        for (Long commitId : commitIds) {
            candidateRows.insertCandidateCommit(id, commitId);
        }

        return id;

    }

    private void linkTakenOut(Long candidateId, Long commitId) {

        Long linkId = candidateRows.insertCandidateCommit(candidateId, commitId);
        jdbc.update("UPDATE candidate_commit SET is_included = FALSE WHERE id = ?", linkId);

    }

    private void insertGrouping(String fingerprint, int version, Instant groupedAt) {

        jdbc.update(
                "INSERT INTO candidate_grouping (user_repository_id, input_fingerprint, grouping_version, grouped_at) VALUES (?, ?, ?, ?)",
                userRepositoryId, fingerprint, version, Timestamp.from(groupedAt));

    }

    private List<Candidate> storedCandidates() {

        return candidates.findByUserRepositoryId(userRepositoryId).stream()
                .sorted(Comparator.comparing(Candidate::getId))
                .toList();

    }

    private List<CandidateCommit> linksOf(Long candidateId) {

        return candidateCommits.findAll().stream()
                .filter(link -> link.getCandidateId().equals(candidateId))
                .sorted(Comparator.comparing(CandidateCommit::getId))
                .toList();

    }

    private List<Long> commitIdsOf(Long candidateId) {

        return linksOf(candidateId).stream().map(CandidateCommit::getCommitId).toList();

    }

    private static List<Long> ids(List<Candidate> candidates) {

        return candidates.stream().map(Candidate::getId).toList();

    }

    // 저장이 다른 요청이 잡은 행을 기다리는 중인지 본다, 그때 다른 요청을 이어 가야 두 작업이 반드시 겹친다
    private boolean anotherRequestIsWaiting() {

        return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted",
                Integer.class) > 0;

    }
}
