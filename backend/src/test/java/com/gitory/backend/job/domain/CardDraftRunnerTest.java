package com.gitory.backend.job.domain;

import com.gitory.backend.common.infra.ClockConfig;
import com.gitory.backend.agent.port.AgentUnavailableException;
import com.gitory.backend.agent.port.CardThemePort;
import com.gitory.backend.agent.port.ExperienceGroupingPort;
import com.gitory.backend.agent.port.StarConfidence;
import com.gitory.backend.agent.port.StarDraft;
import com.gitory.backend.agent.port.StarDraftListener;
import com.gitory.backend.agent.port.StarDraftPort;
import com.gitory.backend.agent.port.StarDraftRequest;
import com.gitory.backend.agent.port.StarEmptyReason;
import com.gitory.backend.agent.port.StarEvidence;
import com.gitory.backend.agent.port.StarEvidenceRef;
import com.gitory.backend.agent.port.StarEvidenceSource;
import com.gitory.backend.agent.port.StarMaterialKind;
import com.gitory.backend.agent.port.StarSlot;
import com.gitory.backend.agent.port.StarSlotDraft;
import com.gitory.backend.agent.port.StarSlotStatus;
import com.gitory.backend.agent.port.StarSourceType;
import com.gitory.backend.agent.port.StarStep;
import com.gitory.backend.agent.port.StarStepActor;
import com.gitory.backend.agent.port.StarStepKind;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.domain.CardDraftWriter;
import com.gitory.backend.card.domain.CardThemeSuggester;
import com.gitory.backend.card.domain.CardVersionReader;
import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.common.infra.ai.AiCallContext;
import com.gitory.backend.common.infra.ai.AiClientException;
import com.gitory.backend.common.infra.ai.AiClientProperties;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.consent.port.DiffEvidence;
import com.gitory.backend.consent.port.DiffEvidencePartialReason;
import com.gitory.backend.consent.port.DiffEvidenceResult;
import com.gitory.backend.consent.port.DiffEvidenceSource;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ActivityStoreService;
import com.gitory.backend.ingest.domain.CollectionStatsService;
import com.gitory.backend.ingest.domain.CommitSummaryService;
import com.gitory.backend.ingest.domain.GroupingMaterialService;
import com.gitory.backend.ingest.domain.StoredActivityService;
import com.gitory.backend.ingest.port.DiffEvidenceRequest;
import com.gitory.backend.ingest.port.RepositoryActivityPort;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateDraftSourceService;
import com.gitory.backend.recommend.domain.CandidateEmptyReasons;
import com.gitory.backend.recommend.domain.CandidateStoreService;
import com.gitory.backend.support.CandidateCardFixtures;
import com.gitory.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** AI 초안을 켠 카드 초안 Job 이 실제 DB 의 카드·후보·커밋으로 카드를 한 장씩 채우는지 본다 */
@DataJpaTest(properties = {
        "gitory.cards.ai-draft.enabled=true",
        "gitory.analysis.grouping.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ClockConfig.class, JobRunner.class, CardDraftRunner.class, CardDraftWriter.class, CardVersionReader.class, JobCancelService.class,
        CardThemeSuggester.class,
        ActivityStoreService.class, GroupingMaterialService.class, CandidateStoreService.class,
        StoredActivityService.class, UserLoginService.class, JobViewFactory.class, JobActivityLog.class, CardDraftLiveLog.class, CandidateEmptyReasons.class,
        CollectionStatsService.class, GeneratedCardQueryService.class, CandidateDraftSourceService.class,
        CommitSummaryService.class})
@EnableConfigurationProperties(AiClientProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class CardDraftRunnerTest {

    private static final String LOGIN = "grow22";
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

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

    @MockitoSpyBean
    CardDraftLiveLog liveLog;

    @MockitoBean
    RepositoryActivityPort activities;

    @MockitoBean
    StarDraftPort star;

    @MockitoBean
    AuditLog auditLog;

    @MockitoBean
    ExperienceGroupingPort grouping;

    @MockitoBean
    GithubCollectionAccessPort githubAccess;

    @MockitoBean
    CardThemePort themes;

    private TestFixtures fixtures;
    private CandidateCardFixtures cardFixtures;
    private Long userId;
    private Long repositoryId;
    private Long userRepositoryId;
    private UUID jobId;

    @BeforeEach
    void setUp() {

        fixtures = new TestFixtures(jdbc);
        cardFixtures = new CandidateCardFixtures(jdbc);
        fixtures.clear();
        userId = fixtures.insertUser(1L, LOGIN);
        repositoryId = fixtures.insertRepository(100L, LOGIN, "gitory");
        userRepositoryId = fixtures.insertUserRepository(userId, repositoryId);
        jobId = cardFixtures.insertDraftJob(userId, userRepositoryId, "QUEUED");

    }

    @Test
    @DisplayName("카드 초안 Job 이 카드마다 부른 AI 호출에는 그 Job 과 카드가 함께 붙는다")
    void marksAiCallsWithTheJobAndCard() {

        Long cardId = cardWithCommits(commit(SHA_A, 30, 10));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        AtomicReference<List<Long>> seen = new AtomicReference<>();
        given(star.draft(any(), any())).willAnswer(invocation -> {
            seen.set(Arrays.asList(AiCallContext.jobId(), AiCallContext.cardId()));
            return allSlots(SHA_A);
        });

        runNext();

        assertThat(seen.get()).containsExactly(job().getId(), cardId);

    }

    @Test
    @DisplayName("카드 진행은 코드를 읽는 동안 READING, AI 가 쓰는 동안 받은 칸 글과 함께 WRITING 이고, 다 쓰면 저장할 초안의 글로 DONE 이 된다")
    void showsSlotTextWhileWritingAndDraftTextWhenDone() {

        Long cardId = cardWithCommits(commit(SHA_A, 30, 10));
        AtomicReference<List<CardDraftLive>> whileReading = new AtomicReference<>();
        given(activities.diffEvidence(any())).willAnswer(invocation -> {
            whileReading.set(liveLog.of(job().getId()));
            return evidenceOf(SHA_A);
        });
        AtomicReference<List<CardDraftLive>> whileWriting = new AtomicReference<>();
        given(star.draft(any(), any())).willAnswer(invocation -> {
            StarDraftListener listener = invocation.getArgument(1);
            listener.slot(0, StarSlot.S, "로그인이 재시작 때", false);
            whileWriting.set(liveLog.of(job().getId()));
            return draftOf(Map.of(
                    StarSlot.S, filled(SHA_A, "로그인이 재시작 때 풀렸다", StarConfidence.HIGH),
                    StarSlot.T, filled(SHA_A, "세션을 오래 유지해야 했다", StarConfidence.HIGH),
                    StarSlot.A, filled(SHA_A, "세션을 DB 에 저장했다", StarConfidence.HIGH),
                    StarSlot.R, empty(StarEmptyReason.NO_EVIDENCE)));
        });

        runNext();

        assertThat(whileReading.get()).extracting(CardDraftLive::state).containsExactly(CardDraftLiveState.READING);
        assertThat(whileWriting.get()).singleElement().satisfies(card -> {
            assertThat(card.state()).isEqualTo(CardDraftLiveState.WRITING);
            assertThat(card.slots()).containsExactly(new CardDraftLive.Slot(StarField.S, 0, "로그인이 재시작 때", false));
        });
        assertThat(liveLog.of(job().getId())).singleElement().satisfies(card -> {
            assertThat(card.cardId()).isEqualTo(jdbc.queryForObject("SELECT public_id FROM card WHERE id = ?", UUID.class, cardId));
            assertThat(card.state()).isEqualTo(CardDraftLiveState.DONE);
            assertThat(card.slots()).extracting(CardDraftLive.Slot::text)
                    .containsExactly("로그인이 재시작 때 풀렸다", "세션을 오래 유지해야 했다", "세션을 DB 에 저장했다", "");
        });

    }

    @Test
    @DisplayName("카드 초안 Job 이 끝나면 카드별 진행에 끝난 시각을 남겨, 10분 뒤 메모리에서 지워지게 한다")
    void marksLiveProgressFinishedWhenJobEnds() {

        cardWithCommits(commit(SHA_A, 30, 10));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(allSlots(SHA_A));

        runNext();

        verify(liveLog).finish(job().getId());

    }

    @Test
    @DisplayName("AI 초안을 켜면 카드의 칸을 근거 커밋과 함께 1번 버전에 저장하고 비운 칸에는 이유를 남기며, 1번 버전을 AI 초안으로 바꾸고 Job 을 완료로 끝낸다")
    void fillsCardAndSucceeds() {

        Long commitA = commit(SHA_A, 30, 10);
        Long commitB = commit(SHA_B, 5, 0);
        Long cardId = cardWithCommits(commitA, commitB);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A, SHA_B));
        given(star.draft(any(), any())).willReturn(draftOf(Map.of(
                StarSlot.S, filled(SHA_A, "로그인이 재시작 때 풀렸다", StarConfidence.HIGH),
                StarSlot.T, filled(SHA_B, "세션을 오래 유지해야 했다", StarConfidence.MEDIUM),
                StarSlot.A, filled(SHA_A, "세션을 DB 에 저장했다", StarConfidence.HIGH),
                StarSlot.R, empty(StarEmptyReason.NO_EVIDENCE))));

        runNext();

        assertThat(statementsOf(cardId)).containsExactly(
                new StatementRow("A", "세션을 DB 에 저장했다", "COMMIT", "HIGH", List.of(commitA)),
                new StatementRow("S", "로그인이 재시작 때 풀렸다", "COMMIT", "HIGH", List.of(commitA)),
                new StatementRow("T", "세션을 오래 유지해야 했다", "COMMIT", "MEDIUM", List.of(commitB)));
        assertThat(notesOf(cardId)).containsExactly(new NoteRow("R", "NO_EVIDENCE", null));
        assertThat(firstVersionSourceOf(cardId)).isEqualTo("AI_DRAFT");
        assertThat(generationPartialOf(cardId)).isFalse();
        AnalysisJob job = job();
        assertThat(job.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job.isPartial()).isFalse();
        assertThat(job.getErrorCode()).isNull();

    }

    @Test
    @DisplayName("diff 근거에는 후보에서 빼지 않은 커밋만 변경량이 큰 순서로 보내고, 줄 수를 모르는 커밋은 맨 뒤로 보내며 PR 번호와 카드 제목을 함께 보낸다")
    void sendsIncludedCommitsLargestFirst() {

        Long small = commit("1".repeat(40), 1, 1);
        Long big = commit("2".repeat(40), 100, 50);
        Long excluded = commit("3".repeat(40), 900, 0);
        Long unknown = commit("4".repeat(40), null, null);
        Long medium = commit("5".repeat(40), 20, 0);
        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "PR", 17, "#17", "후보", "USED");
        for (Long commitId : List.of(small, big, unknown, medium)) {
            cardFixtures.insertCandidateCommit(candidateId, commitId, true);
        }
        cardFixtures.insertCandidateCommit(candidateId, excluded, false);
        cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);
        AtomicReference<DiffEvidenceRequest> sent = new AtomicReference<>();
        given(activities.diffEvidence(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return evidenceOf();
        });

        runNext();

        assertThat(sent.get()).isEqualTo(new DiffEvidenceRequest(userRepositoryId,
                List.of("2".repeat(40), "5".repeat(40), "1".repeat(40), "4".repeat(40)), 17, "후보 카드"));

    }

    @Test
    @DisplayName("후보 커밋이 50개를 넘으면 변경량이 큰 순서로 50개까지만 보낸다")
    void sendsAtMostFiftyCommits() {

        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "5.12", "후보", "USED");
        for (int lines = 1; lines <= 51; lines++) {
            cardFixtures.insertCandidateCommit(candidateId, commit(String.format("%040x", lines), lines, 0), true);
        }
        cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);
        AtomicReference<DiffEvidenceRequest> sent = new AtomicReference<>();
        given(activities.diffEvidence(any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return evidenceOf();
        });

        runNext();

        assertThat(sent.get().commitShas()).hasSize(50)
                .startsWith(String.format("%040x", 51))
                .doesNotContain(String.format("%040x", 1));

    }

    @Test
    @DisplayName("후보에 남은 커밋이 없는 카드는 AI 를 부르지 않고 빈 카드로 두며, Job 은 부분 완료가 아니다")
    void skipsCardWithoutCommits() {

        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "MANUAL", null, "MANUAL", "후보", "USED");
        cardFixtures.insertCandidateCommit(candidateId, commit(SHA_A, 10, 0), false);
        Long cardId = cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);

        runNext();

        verifyNoInteractions(activities, star);
        assertThat(statementsOf(cardId)).isEmpty();
        assertThat(notesOf(cardId)).isEmpty();
        assertThat(firstVersionSourceOf(cardId)).isEqualTo("USER_EDIT");
        assertThat(job().getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job().isPartial()).isFalse();

    }

    @Test
    @DisplayName("STAR 요청에는 후보 공개 id·내 로그인·카드 제목·후보 종류·ref 와 받은 diff 근거를 그대로 보낸다")
    void sendsStarRequestFromCandidateAndEvidence() {

        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "PR", 17, "#17", "후보", "USED");
        cardFixtures.insertCandidateCommit(candidateId, commit(SHA_A, 10, 0), true);
        cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);
        DiffEvidence evidence = new DiffEvidence(SHA_A, "feat: 세션 DB 저장", LOGIN, "+10/-0", "세션을 옮겼다",
                DiffEvidenceSource.COMMIT_MESSAGE, List.of("JDBC"), "https://github.com/grow22/gitory/commit/a",
                List.of("src/main/java/SessionConfig.java"));
        given(activities.diffEvidence(any())).willReturn(new DiffEvidenceResult(List.of(evidence), false, null, List.of()));
        AtomicReference<StarDraftRequest> sent = new AtomicReference<>();
        given(star.draft(any(), any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return allEmpty(StarEmptyReason.NO_EVIDENCE);
        });

        runNext();

        assertThat(sent.get()).isEqualTo(new StarDraftRequest(cardFixtures.publicIdOf(candidateId).toString(), LOGIN,
                "후보 카드", StarSourceType.PR, "#17", List.of(new StarEvidence(SHA_A, "feat: 세션 DB 저장", LOGIN,
                "+10/-0", "세션을 옮겼다", StarEvidenceSource.COMMIT_MESSAGE, List.of("JDBC"),
                "https://github.com/grow22/gitory/commit/a", List.of("src/main/java/SessionConfig.java"))), null));

    }

    @ParameterizedTest
    @MethodSource("pullRequestBodies")
    @DisplayName("후보 PR 의 저장된 본문은 작성자가 카드 주인일 때만 STAR 요청에 싣고, 로그인 대소문자는 가리지 않는다")
    void sendsPullRequestBodyOnlyWhenOwnerWroteIt(boolean stored, String author, String body, String expected) {

        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "PR", 17, "#17", "후보", "USED");
        cardFixtures.insertCandidateCommit(candidateId, commit(SHA_A, 10, 0), true);
        cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);
        if (stored) {
            jdbc.update("INSERT INTO pull_request (repository_id, github_pr_number, title, body_excerpt, state, author_login,"
                    + " base_branch, head_branch, opened_at) VALUES (?, 17, 'PR', ?, 'CLOSED', ?, 'develop', 'feature', now())",
                    repositoryId, body, author);
        }
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        AtomicReference<StarDraftRequest> sent = new AtomicReference<>();
        given(star.draft(any(), any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return allEmpty(StarEmptyReason.NO_EVIDENCE);
        });

        runNext();

        assertThat(sent.get().pullRequestBody()).isEqualTo(expected);

    }

    @Test
    @DisplayName("다른 저장소에 같은 번호의 PR 이 있어도 카드가 속한 저장소의 PR 본문만 싣는다")
    void readsPullRequestOnlyFromCardRepository() {

        Long otherRepositoryId = fixtures.insertRepository(101L, LOGIN, "other");
        fixtures.insertUserRepository(userId, otherRepositoryId);
        jdbc.update("INSERT INTO pull_request (repository_id, github_pr_number, title, body_excerpt, state, author_login,"
                + " base_branch, head_branch, opened_at) VALUES (?, 17, 'PR', '다른 저장소 본문', 'CLOSED', ?, 'develop', 'feature', now())",
                otherRepositoryId, LOGIN);
        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "PR", 17, "#17", "후보", "USED");
        cardFixtures.insertCandidateCommit(candidateId, commit(SHA_A, 10, 0), true);
        cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        AtomicReference<StarDraftRequest> sent = new AtomicReference<>();
        given(star.draft(any(), any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return allEmpty(StarEmptyReason.NO_EVIDENCE);
        });

        runNext();

        assertThat(sent.get().pullRequestBody()).isNull();

    }

    static Stream<Arguments> pullRequestBodies() {

        return Stream.of(
                argumentSet("카드 주인이 쓴 PR", true, LOGIN, "## 고민한 점 폴링을 골랐다", "## 고민한 점 폴링을 골랐다"),
                argumentSet("로그인 대소문자만 다른 카드 주인", true, "Grow22", "본문", "본문"),
                argumentSet("남이 쓴 PR", true, "taehun0208", "본문", null),
                argumentSet("공백뿐인 본문", true, LOGIN, "   ", null),
                argumentSet("본문 없는 PR", true, LOGIN, null, null),
                argumentSet("저장되지 않은 PR", false, LOGIN, "본문", null));

    }

    @Test
    @DisplayName("AI 가 준 초안 단계는 칸과 같은 트랜잭션에서 1번 버전에 순서대로 남기고, 이유는 60자로 자른다")
    void storesDraftStepsOnFirstVersion() {

        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "PR", 17, "#17", "후보", "USED");
        cardFixtures.insertCandidateCommit(candidateId, commit(SHA_A, 10, 0), true);
        Long cardId = cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        StarDraft empty = allEmpty(StarEmptyReason.NO_EVIDENCE);
        given(star.draft(any(), any())).willReturn(new StarDraft(empty.title(), empty.slots(), empty.missingFields(), null,
                List.of(), List.of(), List.of(), List.of(
                        new StarStep(0, StarStepActor.MODEL, StarStepKind.DRAFT, null, null, null, null, null),
                        new StarStep(0, StarStepActor.CODE, StarStepKind.FLAG, StarSlot.R, "UNSOURCED_NUMBER", "50%",
                                null, null),
                        new StarStep(1, StarStepActor.MODEL, StarStepKind.OPEN, StarSlot.S, null, "이슈 #98",
                                StarMaterialKind.ISSUE, "가".repeat(80)),
                        new StarStep(2, StarStepActor.CODE, StarStepKind.REVERT, StarSlot.S, "EMPTY", null, null,
                                null))));

        runNext();

        assertThat(jdbc.queryForList("""
                SELECT version_no, seq, round_no, actor, kind, star_slot, code, detail, material, reason
                FROM card_draft_step WHERE card_id = ? ORDER BY seq
                """, cardId))
                .extracting(row -> tuple(row.get("version_no"), row.get("seq"), row.get("round_no"), row.get("actor"),
                        row.get("kind"), row.get("star_slot"), row.get("code"), row.get("detail"), row.get("material"),
                        row.get("reason")))
                .containsExactly(
                        tuple(1, 0, 0, "MODEL", "DRAFT", null, null, null, null, null),
                        tuple(1, 1, 0, "CODE", "FLAG", "R", "UNSOURCED_NUMBER", "50%", null,
                                null),
                        tuple(1, 2, 1, "MODEL", "OPEN", "S", null, "이슈 #98", "ISSUE",
                                "가".repeat(60)),
                        tuple(1, 3, 2, "CODE", "REVERT", "S", "EMPTY", null, null, null));

    }

    @Test
    @DisplayName("ref 가 빈 후보는 STAR 요청의 ref 를 비워 보낸다")
    void sendsBlankRefAsNull() {

        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "COMMIT_CLUSTER", null, "", "후보", "USED");
        cardFixtures.insertCandidateCommit(candidateId, commit(SHA_A, 10, 0), true);
        cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        AtomicReference<StarDraftRequest> sent = new AtomicReference<>();
        given(star.draft(any(), any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return allEmpty(StarEmptyReason.NO_EVIDENCE);
        });

        runNext();

        assertThat(sent.get().sourceType()).isEqualTo(StarSourceType.COMMIT_CLUSTER);
        assertThat(sent.get().sourceRef()).isNull();

    }

    @Test
    @DisplayName("diff 근거가 하나도 없으면 STAR 를 부르지 않고 사용자가 쓰지 않은 빈 칸에 근거 없음을 남기며, Job 은 근거 없음 부분 완료다")
    void noEvidenceSkipsStarAndNotesEmptySlots() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        fixtures.insertStatement(cardId, 1, "T", "내가 쓴 과제", "USER_STATED", null);
        given(activities.diffEvidence(any())).willReturn(
                new DiffEvidenceResult(List.of(), true, DiffEvidencePartialReason.COMMIT_UNAVAILABLE, List.of(SHA_A)));

        runNext();

        verifyNoInteractions(star);
        assertThat(notesOf(cardId)).containsExactly(
                new NoteRow("A", "NO_EVIDENCE", null),
                new NoteRow("R", "NO_EVIDENCE", null),
                new NoteRow("S", "NO_EVIDENCE", null));
        assertThat(firstVersionSourceOf(cardId)).isEqualTo("AI_DRAFT");
        assertThat(job().isPartial()).isTrue();
        assertThat(job().getErrorCode()).isEqualTo(JobErrorCode.EVIDENCE_MISSING);

    }

    @Test
    @DisplayName("사용자가 이미 쓴 과제(T) 칸은 AI 의 T 로 덮어쓰지 않고 그 칸 메모도 남기지 않는다")
    void keepsUserTask() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        fixtures.insertStatement(cardId, 1, "T", "내가 쓴 과제", "USER_STATED", null);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(draftOf(Map.of(
                StarSlot.S, filled(SHA_A, "상황", StarConfidence.HIGH),
                StarSlot.T, empty(StarEmptyReason.TIMEOUT),
                StarSlot.A, filled(SHA_A, "행동", StarConfidence.HIGH),
                StarSlot.R, filled(SHA_A, "결과", StarConfidence.HIGH))));

        runNext();

        assertThat(statementsOf(cardId)).extracting(StatementRow::slot, StatementRow::body, StatementRow::evidenceType)
                .contains(tuple("T", "내가 쓴 과제", "USER_STATED"))
                .hasSize(4);
        assertThat(notesOf(cardId)).isEmpty();
        assertThat(generationPartialOf(cardId)).isFalse();
        assertThat(job().isPartial()).isFalse();

    }

    @Test
    @DisplayName("diff 를 읽어 쓴 요약만 그 커밋에 남기고, 커밋 메시지로 대신한 요약과 빈 요약은 남기지 않는다")
    void storesOnlyDiffSummaries() {

        String shaC = "c".repeat(40);
        Long commitA = commit(SHA_A, 10, 0);
        Long commitB = commit(SHA_B, 5, 0);
        Long commitC = commit(shaC, 3, 0);
        cardWithCommits(commitA, commitB, commitC);
        given(activities.diffEvidence(any())).willReturn(new DiffEvidenceResult(List.of(
                summary(SHA_A, " 세션 저장소를 DB 로 옮겼다 ", DiffEvidenceSource.DIFF),
                summary(SHA_B, "feat: 세션 저장", DiffEvidenceSource.COMMIT_MESSAGE),
                summary(shaC, "  ", DiffEvidenceSource.DIFF)), false, null, List.of()));
        given(star.draft(any(), any())).willReturn(allSlots(SHA_A));

        runNext();

        assertThat(diffSummaryOf(commitA)).isEqualTo("세션 저장소를 DB 로 옮겼다");
        assertThat(diffSummaryOf(commitB)).isNull();
        assertThat(diffSummaryOf(commitC)).isNull();

    }

    @Test
    @DisplayName("이미 요약이 있는 커밋을 다시 요약하면 새 요약으로 바꾼다")
    void replacesOlderDiffSummary() {

        Long commitA = commit(SHA_A, 10, 0);
        jdbc.update("UPDATE git_commit SET diff_summary = '옛 요약' WHERE id = ?", commitA);
        cardWithCommits(commitA);
        given(activities.diffEvidence(any())).willReturn(new DiffEvidenceResult(List.of(
                summary(SHA_A, "새 요약", DiffEvidenceSource.DIFF)), false, null, List.of()));
        given(star.draft(any(), any())).willReturn(allSlots(SHA_A));

        runNext();

        assertThat(diffSummaryOf(commitA)).isEqualTo("새 요약");

    }

    @Test
    @DisplayName("보낸 커밋 중 하나에도 이어지지 않는 문장은 저장하지 않고 그 칸을 근거 없음으로 비운다")
    void dropsSentenceWithoutLinkableEvidence() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(draftOf(Map.of(
                StarSlot.S, filled("f".repeat(40), "지어낸 상황", StarConfidence.HIGH),
                StarSlot.T, filled(SHA_A, "과제", StarConfidence.HIGH),
                StarSlot.A, filled(SHA_A, "행동", StarConfidence.HIGH),
                StarSlot.R, filled(SHA_A, "결과", StarConfidence.HIGH))));

        runNext();

        assertThat(statementsOf(cardId)).extracting(StatementRow::slot).containsExactly("A", "R", "T");
        assertThat(notesOf(cardId)).containsExactly(new NoteRow("S", "NO_EVIDENCE", null));

    }

    @Test
    @DisplayName("AI 가 짧게 준 sha 는 보낸 커밋 하나와만 맞을 때 근거로 잇고, 둘 이상과 맞으면 잇지 않는다")
    void linksShortShaOnlyWhenUnique() {

        String first = "abc1234" + "0".repeat(33);
        String second = "abc1234" + "1".repeat(33);
        String third = "def5678" + "0".repeat(33);
        Long firstId = commit(first, 30, 0);
        Long secondId = commit(second, 20, 0);
        Long thirdId = commit(third, 10, 0);
        Long cardId = cardWithCommits(firstId, secondId, thirdId);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(first, second, third));
        given(star.draft(any(), any())).willReturn(draftOf(Map.of(
                StarSlot.S, filled("DEF5678", "상황", StarConfidence.HIGH),
                StarSlot.T, filled("abc12340", "과제", StarConfidence.HIGH),
                StarSlot.A, filled("abc1234", "행동", StarConfidence.HIGH),
                StarSlot.R, empty(StarEmptyReason.NO_EVIDENCE))));

        runNext();

        assertThat(statementsOf(cardId)).extracting(StatementRow::slot, StatementRow::commitIds).containsExactly(
                tuple("S", List.of(thirdId)),
                tuple("T", List.of(firstId)));
        assertThat(notesOf(cardId)).contains(new NoteRow("A", "NO_EVIDENCE", null));

    }

    @Test
    @DisplayName("확인 필요 칸은 LOW 문장과 함께 그 이유를, 과장이라 비운 칸은 그 이유 코드를 칸 메모로 남기고 카드는 작성 중으로 둔다")
    void notesReviewAndDropReasons() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(draftOf(Map.of(
                StarSlot.S, filled(SHA_A, "상황", StarConfidence.HIGH),
                StarSlot.T, filled(SHA_A, "과제", StarConfidence.HIGH),
                StarSlot.A, needsReview(SHA_A, "30% 빨라졌다", "근거에 없는 수치: 30%"),
                StarSlot.R, empty(StarEmptyReason.OVERCLAIM))));

        runNext();

        assertThat(statementsOf(cardId)).contains(
                new StatementRow("A", "30% 빨라졌다", "COMMIT", "LOW", List.of(idOf(SHA_A))));
        assertThat(notesOf(cardId)).containsExactly(
                new NoteRow("A", null, "근거에 없는 수치: 30%"),
                new NoteRow("R", "OVERCLAIM", null));
        assertThat(statusOf(cardId)).isEqualTo("DRAFT");

    }

    @Test
    @DisplayName("AI 초안의 네 칸이 확인 필요 없이 모두 차면 사용자가 확정을 누르지 않아도 카드를 확정한다")
    void completeDraftConfirmsCard() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(allSlots(SHA_A));

        runNext();

        assertThat(statusOf(cardId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM card WHERE id = ?", Object.class, cardId)).isNotNull();

    }

    @Test
    @DisplayName("시간이 모자라 비운 칸이 있으면 카드를 일부만 만든 카드로 표시하고 Job 은 시간 초과 부분 완료다")
    void timeoutMarksCardPartial() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(draftOf(Map.of(
                StarSlot.S, filled(SHA_A, "상황", StarConfidence.HIGH),
                StarSlot.T, filled(SHA_A, "과제", StarConfidence.HIGH),
                StarSlot.A, filled(SHA_A, "행동", StarConfidence.HIGH),
                StarSlot.R, empty(StarEmptyReason.TIMEOUT))));

        runNext();

        assertThat(generationPartialOf(cardId)).isTrue();
        assertThat(notesOf(cardId)).containsExactly(new NoteRow("R", "TIMEOUT", null));
        assertThat(job().isPartial()).isTrue();
        assertThat(job().getErrorCode()).isEqualTo(JobErrorCode.DRAFT_TIMEOUT);

    }

    @Test
    @DisplayName("한 카드의 AI 가 실패해도 그 카드는 빈 채로 두고 다음 카드를 채우며, Job 은 AI 오류 부분 완료다")
    void failedCardDoesNotStopNextCard() {

        Long failed = cardWithCommits(commit(SHA_A, 10, 0));
        Long drafted = cardWithCommits(commit(SHA_B, 10, 0));
        given(activities.diffEvidence(any()))
                .willThrow(new AiClientException("GITHUB_API_ERROR", true, 502))
                .willReturn(evidenceOf(SHA_B));
        given(star.draft(any(), any())).willReturn(allFilled(SHA_B));

        runNext();

        assertThat(statementsOf(failed)).isEmpty();
        assertThat(firstVersionSourceOf(failed)).isEqualTo("USER_EDIT");
        assertThat(statementsOf(drafted)).hasSize(4);
        assertThat(generationFailureOf(failed)).isEqualTo("INTERNAL_ERROR");
        assertThat(generationFailureOf(drafted)).isNull();
        assertThat(job().getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job().isPartial()).isTrue();
        assertThat(job().getErrorCode()).isEqualTo(JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("모든 카드의 AI 가 잠시 응답하지 못하면 카드에 그 이유를 남기고, Job 은 다시 시도할 수 있는 AI_UNAVAILABLE 로 실패한다")
    void failsWhenEveryCardFails() {

        Long first = cardWithCommits(commit(SHA_A, 10, 0));
        Long second = cardWithCommits(commit(SHA_B, 10, 0));
        given(activities.diffEvidence(any())).willAnswer(invocation -> evidenceOf(SHA_A, SHA_B));
        given(star.draft(any(), any())).willThrow(new AgentUnavailableException("AI STAR 초안 실패", true, false, null));

        runNext();

        assertThat(statementsOf(first)).isEmpty();
        assertThat(statementsOf(second)).isEmpty();
        assertThat(generationFailureOf(first)).isEqualTo("AI_UNAVAILABLE");
        assertThat(generationFailureOf(second)).isEqualTo("AI_UNAVAILABLE");
        assertThat(job().getState()).isEqualTo(JobState.FAILED);
        assertThat(job().getErrorCode()).isEqualTo(JobErrorCode.AI_UNAVAILABLE);

    }

    @Test
    @DisplayName("GitHub 이 토큰을 거절하면 카드에 GITHUB_UNAUTHORIZED 를 남기고, Job 은 다시 연결해야 하는 GITHUB_UNAUTHORIZED 로 실패한다")
    void marksTokenRejectionOnCardAndJob() {

        Long first = cardWithCommits(commit(SHA_A, 10, 0));
        Long second = cardWithCommits(commit(SHA_B, 10, 0));
        given(activities.diffEvidence(any())).willThrow(new AiClientException("GITHUB_UNAUTHORIZED", false, null));

        runNext();

        assertThat(generationFailureOf(first)).isEqualTo("GITHUB_UNAUTHORIZED");
        assertThat(generationFailureOf(second)).isEqualTo("GITHUB_UNAUTHORIZED");
        assertThat(job().getState()).isEqualTo(JobState.FAILED);
        assertThat(job().getErrorCode()).isEqualTo(JobErrorCode.GITHUB_UNAUTHORIZED);

    }

    @Test
    @DisplayName("다시 해도 같은 AI 오류(콘텐츠 제한·잘못된 응답)로 모든 카드가 실패하면 카드에 INTERNAL_ERROR 를 남기고 Job 은 INTERNAL_ERROR 로 실패한다")
    void failsWithInternalErrorWhenFailureIsNotRetryable() {

        Long card = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willAnswer(invocation -> evidenceOf(SHA_A));
        given(star.draft(any(), any())).willThrow(new AgentUnavailableException("AI STAR 초안 실패", false, true, null));

        runNext();

        assertThat(generationFailureOf(card)).isEqualTo("INTERNAL_ERROR");
        assertThat(job().getState()).isEqualTo(JobState.FAILED);
        assertThat(job().getErrorCode()).isEqualTo(JobErrorCode.INTERNAL_ERROR);

    }

    @Test
    @DisplayName("카드를 한 장 마칠 때마다 진행 숫자를 남겨, 둘째 카드의 STAR 를 기다릴 때는 diff 근거는 둘 다 받았고 저장은 한 장이며 끝나면 모두 완료다")
    void recordsProgressPerCard() {

        cardWithCommits(commit(SHA_A, 10, 0));
        cardWithCommits(commit(SHA_B, 10, 0));
        given(activities.diffEvidence(any())).willAnswer(invocation -> evidenceOf(SHA_A, SHA_B));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<JobStep>> whileSecond = new AtomicReference<>();
        given(star.draft(any(), any())).willAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                whileSecond.set(job().getSteps());
            }
            return allEmpty(StarEmptyReason.NO_EVIDENCE);
        });

        runNext();

        assertThat(whileSecond.get()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 2, 2),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.RUNNING, 1, 2),
                new JobStep(JobStepKey.REASON, JobStepState.RUNNING, 1, 2));
        assertThat(job().getSteps()).containsExactly(
                new JobStep(JobStepKey.COMMITS, JobStepState.DONE, 2, 2),
                new JobStep(JobStepKey.PR_REVIEW, JobStepState.SKIPPED, 0, null),
                new JobStep(JobStepKey.COMPRESS, JobStepState.DONE, 2, 2),
                new JobStep(JobStepKey.REASON, JobStepState.DONE, 2, 2));

    }

    @Test
    @DisplayName("STAR 를 기다리는 사이 Job 이 취소되면 그 카드를 저장하지 않고 다음 카드도 부르지 않으며, 실패 기록 없이 Job 은 취소로 남는다")
    void stopsWhenCanceledWhileWaiting(CapturedOutput output) {

        Long first = cardWithCommits(commit(SHA_A, 10, 0));
        Long second = cardWithCommits(commit(SHA_B, 10, 0));
        given(activities.diffEvidence(any())).willAnswer(invocation -> evidenceOf(SHA_A, SHA_B));
        given(star.draft(any(), any())).willAnswer(invocation -> {
            cancelService.cancel(jobId, userId, LOGIN);
            return allFilled(SHA_A);
        });

        runNext();

        verify(star, times(1)).draft(any(), any());
        verify(activities, times(1)).diffEvidence(any());
        assertThat(statementsOf(first)).isEmpty();
        assertThat(statementsOf(second)).isEmpty();
        assertThat(firstVersionSourceOf(first)).isEqualTo("USER_EDIT");
        assertThat(job().getState()).isEqualTo(JobState.CANCELED);
        assertThat(output).doesNotContain("채우지 못했다", "로 끝낸다");

    }

    @Test
    @DisplayName("서버가 다시 켜져 같은 Job 이 다시 돌면 이미 AI 초안을 담은 카드는 AI 를 부르지 않고 건너뛴다")
    void skipsAlreadyDraftedCard() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        jdbc.update("UPDATE card_version SET source = 'AI_DRAFT' WHERE card_id = ?", cardId);

        runNext();

        verifyNoInteractions(activities, star);
        assertThat(job().getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(job().isPartial()).isFalse();

    }

    @Test
    @DisplayName("현재 버전이 1번이 아닌 카드에는 AI 초안을 담지 않고 1번 버전을 그대로 둔다")
    void doesNotWriteIntoCardPastFirstVersion() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(allFilled(SHA_A));

        runNext();

        assertThat(statementsOf(cardId)).isEmpty();
        assertThat(notesOf(cardId)).isEmpty();
        assertThat(firstVersionSourceOf(cardId)).isEqualTo("USER_EDIT");

    }

    @Test
    @DisplayName("서버가 다시 켜져 실패 표시가 남은 카드에 STAR 초안을 담으면 실패 표시를 지운다")
    void clearsFailureWhenDraftIsWritten() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        jdbc.update("UPDATE card SET generation_failure = 'AI_UNAVAILABLE' WHERE id = ?", cardId);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(allFilled(SHA_A));

        runNext();

        assertThat(statementsOf(cardId)).hasSize(4);
        assertThat(generationFailureOf(cardId)).isNull();

    }

    @Test
    @DisplayName("근거가 없어 근거 없음만 담아도 초안 시도가 끝났으므로 실패 표시를 지운다")
    void clearsFailureWhenOnlyNoEvidenceIsWritten() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        jdbc.update("UPDATE card SET generation_failure = 'AI_UNAVAILABLE' WHERE id = ?", cardId);
        given(activities.diffEvidence(any())).willReturn(evidenceOf());

        runNext();

        assertThat(firstVersionSourceOf(cardId)).isEqualTo("AI_DRAFT");
        assertThat(generationFailureOf(cardId)).isNull();

    }

    @Test
    @DisplayName("현재 버전이 1번이 아니어서 초안을 담지 못한 카드는 실패 표시를 그대로 둔다")
    void keepsFailureWhenDraftIsNotWritten() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        fixtures.insertCardVersion(cardId, 2, "USER_EDIT", false);
        jdbc.update("UPDATE card SET generation_failure = 'AI_UNAVAILABLE' WHERE id = ?", cardId);
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(allFilled(SHA_A));

        runNext();

        assertThat(generationFailureOf(cardId)).isEqualTo("AI_UNAVAILABLE");

    }

    @Test
    @DisplayName("AI 가 보낸 분야·기술 태그를 받은 순서대로 카드에 저장한다")
    void savesTagsFromDraft() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(taggedDraft(SHA_A, List.of("DB", "BACKEND"), List.of("SQL", "JAVA")));

        runNext();

        assertThat(areasOf(cardId)).containsExactly("DB", "BACKEND");
        assertThat(techsOf(cardId)).containsExactly("SQL", "JAVA");

    }

    @Test
    @DisplayName("목록 밖의 태그 값은 버리고 나머지만 저장하며, 버린 값을 WARN 로그 한 줄로 남긴다")
    void dropsUnknownTags(CapturedOutput output) {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(taggedDraft(SHA_A, List.of("backend", "BACKEND"), List.of("PHP", "JAVA")));

        runNext();

        assertThat(areasOf(cardId)).containsExactly("BACKEND");
        assertThat(techsOf(cardId)).containsExactly("JAVA");
        assertThat(output.getAll()).contains("CardArea 목록 밖의 값 1개를 버린다: [backend]")
                .contains("CardTech 목록 밖의 값 1개를 버린다: [PHP]");
        assertThat(job().getState()).isEqualTo(JobState.SUCCEEDED);

    }

    @Test
    @DisplayName("같은 태그가 두 번 오면 처음 나온 자리에 한 번만 저장한다")
    void savesRepeatedTagOnce() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(taggedDraft(SHA_A, List.of("BACKEND", "BACKEND"),
                List.of("JAVA", "SQL", "JAVA")));

        runNext();

        assertThat(areasOf(cardId)).containsExactly("BACKEND");
        assertThat(techsOf(cardId)).containsExactly("JAVA", "SQL");

    }

    @Test
    @DisplayName("분야가 2개, 기술이 8개를 넘으면 앞에서부터 그 개수만 남긴다")
    void keepsTagsUpToLimit() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(taggedDraft(SHA_A, List.of("BACKEND", "DB", "DEVOPS"),
                List.of("JAVA", "SPRING", "JPA", "MYSQL", "REDIS", "KAFKA", "DOCKER", "AWS", "GITHUB_ACTIONS")));

        runNext();

        assertThat(areasOf(cardId)).containsExactly("BACKEND", "DB");
        assertThat(techsOf(cardId)).containsExactly("JAVA", "SPRING", "JPA", "MYSQL", "REDIS", "KAFKA", "DOCKER", "AWS");

    }

    @Test
    @DisplayName("AI 응답에 태그가 없으면 분야·기술을 빈 배열로 저장한다")
    void savesEmptyTagsWhenDraftHasNone() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(evidenceOf(SHA_A));
        given(star.draft(any(), any())).willReturn(allFilled(SHA_A));

        runNext();

        assertThat(areasOf(cardId)).isEmpty();
        assertThat(techsOf(cardId)).isEmpty();
        assertThat(firstVersionSourceOf(cardId)).isEqualTo("AI_DRAFT");

    }

    @Test
    @DisplayName("diff 근거가 없어 STAR 를 부르지 않은 카드는 태그가 빈 배열이다")
    void leavesTagsEmptyWithoutEvidence() {

        Long cardId = cardWithCommits(commit(SHA_A, 10, 0));
        given(activities.diffEvidence(any())).willReturn(
                new DiffEvidenceResult(List.of(), true, DiffEvidencePartialReason.COMMIT_UNAVAILABLE, List.of(SHA_A)));

        runNext();

        verifyNoInteractions(star);
        assertThat(areasOf(cardId)).isEmpty();
        assertThat(techsOf(cardId)).isEmpty();

    }

    private void runNext() {

        runner.claimNext().ifPresent(runner::run);

    }

    private AnalysisJob job() {

        return jobs.findByPublicId(jobId).orElseThrow();

    }

    private Long commit(String sha, Integer additions, Integer deletions) {

        return jdbc.queryForObject(
                "INSERT INTO git_commit (repository_id, sha, additions, deletions, author_login) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, repositoryId, sha, additions, deletions, LOGIN);

    }

    private Long idOf(String sha) {

        return jdbc.queryForObject("SELECT id FROM git_commit WHERE sha = ?", Long.class, sha);

    }

    /** 커밋을 모두 포함한 PR 후보와, 이 Job 이 채울 그 후보의 카드를 만든다 */
    private String statusOf(Long cardId) {

        return jdbc.queryForObject("SELECT status FROM card WHERE id = ?", String.class, cardId);

    }

    private Long cardWithCommits(Long... commitIds) {

        Long candidateId = cardFixtures.insertCandidate(userRepositoryId, "PR", 17, "#17", "후보", "USED");
        for (Long commitId : commitIds) {
            cardFixtures.insertCandidateCommit(candidateId, commitId, true);
        }

        return cardFixtures.insertCandidateCard(userId, userRepositoryId, candidateId, jobId, false);

    }

    private List<StatementRow> statementsOf(Long cardId) {

        return jdbc.query("SELECT id, star_slot, body, evidence_type, confidence FROM card_statement WHERE card_id = ? AND version_no = 1 ORDER BY star_slot",
                (row, rowNum) -> new StatementRow(row.getString("star_slot"), row.getString("body"),
                        row.getString("evidence_type"), row.getString("confidence"),
                        jdbc.queryForList("SELECT commit_id FROM statement_evidence WHERE statement_id = ? ORDER BY id",
                                Long.class, row.getLong("id"))), cardId);

    }

    private List<NoteRow> notesOf(Long cardId) {

        return jdbc.query("SELECT star_slot, drop_reason, review_reason FROM card_slot_note WHERE card_id = ? AND version_no = 1 ORDER BY star_slot",
                (row, rowNum) -> new NoteRow(row.getString("star_slot"), row.getString("drop_reason"),
                        row.getString("review_reason")), cardId);

    }

    private String firstVersionSourceOf(Long cardId) {

        return jdbc.queryForObject("SELECT source FROM card_version WHERE card_id = ? AND version_no = 1", String.class,
                cardId);

    }

    private String generationFailureOf(Long cardId) {

        return jdbc.queryForObject("SELECT generation_failure FROM card WHERE id = ?", String.class, cardId);

    }

    private boolean generationPartialOf(Long cardId) {

        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT generation_partial FROM card WHERE id = ?",
                Boolean.class, cardId));

    }

    private static DiffEvidence summary(String sha, String text, DiffEvidenceSource source) {

        return new DiffEvidence(sha, "feat: " + sha.substring(0, 7), LOGIN, "+10/-0", text, source, List.of(), null,
                List.of());

    }

    private static StarDraft allSlots(String sha) {

        return draftOf(Map.of(
                StarSlot.S, filled(sha, "상황", StarConfidence.HIGH),
                StarSlot.T, filled(sha, "과제", StarConfidence.HIGH),
                StarSlot.A, filled(sha, "행동", StarConfidence.HIGH),
                StarSlot.R, filled(sha, "결과", StarConfidence.HIGH)));

    }

    private String diffSummaryOf(Long commitId) {

        return jdbc.queryForObject("SELECT diff_summary FROM git_commit WHERE id = ?", String.class, commitId);

    }

    private static DiffEvidenceResult evidenceOf(String... shas) {

        List<DiffEvidence> evidence = new ArrayList<>();
        for (String sha : shas) {
            evidence.add(new DiffEvidence(sha, "feat: " + sha.substring(0, 7), LOGIN, "+10/-0", "요약",
                    DiffEvidenceSource.DIFF, List.of(), null, List.of()));
        }

        return new DiffEvidenceResult(evidence, false, null, List.of());

    }

    private static StarSlotDraft filled(String sha, String text, StarConfidence confidence) {

        return new StarSlotDraft(StarSlotStatus.FILLED, text, confidence,
                List.of(new StarEvidenceRef(sha, "feat: 근거", null)), null, null, null);

    }

    private static StarSlotDraft needsReview(String sha, String text, String reason) {

        return new StarSlotDraft(StarSlotStatus.NEEDS_REVIEW, text, StarConfidence.LOW,
                List.of(new StarEvidenceRef(sha, "feat: 근거", null)), null, null, reason);

    }

    private static StarSlotDraft empty(StarEmptyReason reason) {

        return new StarSlotDraft(StarSlotStatus.EMPTY, null, null, List.of(), reason, "근거가 없습니다.", null);

    }

    private static StarDraft draftOf(Map<StarSlot, StarSlotDraft> slots) {

        return draftOf(slots, List.of(), List.of());

    }

    private static StarDraft draftOf(Map<StarSlot, StarSlotDraft> slots, List<String> areas, List<String> techs) {

        List<StarSlot> missing = Arrays.stream(StarSlot.values())
                .filter(slot -> slots.get(slot).status() == StarSlotStatus.EMPTY)
                .toList();

        return new StarDraft("후보 카드", slots, missing, null, List.of(), areas, techs, List.of());

    }

    private static StarDraft taggedDraft(String sha, List<String> areas, List<String> techs) {

        return draftOf(Map.of(
                StarSlot.S, filled(sha, "상황", StarConfidence.HIGH),
                StarSlot.T, filled(sha, "과제", StarConfidence.HIGH),
                StarSlot.A, filled(sha, "행동", StarConfidence.HIGH),
                StarSlot.R, filled(sha, "결과", StarConfidence.HIGH)), areas, techs);

    }

    private List<String> areasOf(Long cardId) {

        return jdbc.queryForList("SELECT t.tag FROM card, unnest(card.areas) WITH ORDINALITY AS t(tag, n) WHERE card.id = ? ORDER BY t.n",
                String.class, cardId);

    }

    private List<String> techsOf(Long cardId) {

        return jdbc.queryForList("SELECT t.tag FROM card, unnest(card.techs) WITH ORDINALITY AS t(tag, n) WHERE card.id = ? ORDER BY t.n",
                String.class, cardId);

    }

    private static StarDraft allFilled(String sha) {

        return draftOf(Map.of(
                StarSlot.S, filled(sha, "상황", StarConfidence.HIGH),
                StarSlot.T, filled(sha, "과제", StarConfidence.HIGH),
                StarSlot.A, filled(sha, "행동", StarConfidence.HIGH),
                StarSlot.R, filled(sha, "결과", StarConfidence.HIGH)));

    }

    private static StarDraft allEmpty(StarEmptyReason reason) {

        return draftOf(Map.of(StarSlot.S, empty(reason), StarSlot.T, empty(reason), StarSlot.A, empty(reason),
                StarSlot.R, empty(reason)));

    }

    private record StatementRow(String slot, String body, String evidenceType, String confidence, List<Long> commitIds) {
    }

    private record NoteRow(String slot, String dropReason, String reviewReason) {
    }
}
