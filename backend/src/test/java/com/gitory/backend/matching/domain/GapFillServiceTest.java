package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardLabel;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.common.infra.ratelimit.RateLimitedAction;
import com.gitory.backend.common.infra.ratelimit.UserRateLimiter;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.port.GithubCollectionAccessPort;
import com.gitory.backend.ingest.domain.ConnectedRepositoryReference;
import com.gitory.backend.ingest.domain.RepositoryReferenceService;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchGapFillRepository;
import com.gitory.backend.recommend.domain.UnusedCandidate;
import com.gitory.backend.recommend.domain.UnusedCandidateReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GapFillServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Long USER = 1L;
    private static final String LOGIN = "grow22";
    private static final Long POSTING = 30L;
    private static final UUID POSTING_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<GapFillCondition>> CONDITIONS = new TypeReference<>() {
    };

    private final MatchDetailService details = mock(MatchDetailService.class);
    private final JobPostingRepository postings = mock(JobPostingRepository.class);
    private final MatchingCardReader cards = mock(MatchingCardReader.class);
    private final UnusedCandidateReader candidates = mock(UnusedCandidateReader.class);
    private final RepositoryReferenceService repositories = mock(RepositoryReferenceService.class);
    private final GapFillPort port = mock(GapFillPort.class);
    private final MatchGapFillRepository fills = mock(MatchGapFillRepository.class);
    private final UserRateLimiter rateLimiter = mock(UserRateLimiter.class);
    private final GapFillLiveLog liveLog = new GapFillLiveLog();
    private final List<Runnable> started = new ArrayList<>();
    private final GithubCollectionAccessPort githubAccess = mock(GithubCollectionAccessPort.class);
    private final GapFillService service = new GapFillService(details, postings, cards, candidates, repositories,
            githubAccess, port, fills, liveLog, rateLimiter,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), MAPPER, Clock.fixed(NOW, ZoneOffset.UTC),
            started::add);

    private final List<MatchCondition> conditions = new ArrayList<>();
    private final List<MatchingCard> confirmed = new ArrayList<>();
    private final List<MatchingCard> drafts = new ArrayList<>();
    private final Map<Long, MatchingCardLabel> labels = new HashMap<>();
    private final List<ConnectedRepositoryReference> repos = new ArrayList<>();
    private final List<UnusedCandidate> unused = new ArrayList<>();
    private MatchGapFill row;

    @BeforeEach
    void setUp() {

        when(details.detail(POSTING_ID, USER)).thenAnswer(call -> new MatchDetail(target(), null, null, false, null,
                List.copyOf(conditions)));
        when(postings.findIdByPublicId(POSTING_ID)).thenReturn(Optional.of(POSTING));
        when(cards.confirmedOf(USER)).thenAnswer(call -> List.copyOf(confirmed));
        when(cards.draftsOf(USER, GapFillService.MAX_DRAFT_CARDS)).thenAnswer(call -> List.copyOf(drafts));
        when(cards.labelsOf(any())).thenAnswer(call -> labels);
        when(repositories.connectedOf(USER, LOGIN, GapFillService.MAX_REPOS)).thenAnswer(call -> List.copyOf(repos));
        when(candidates.of(any(), eq(GapFillService.MAX_CANDIDATES), eq(GapFillService.MAX_COMMIT_MESSAGES)))
                .thenAnswer(call -> List.copyOf(unused));
        when(fills.findByUserIdAndJobPostingId(USER, POSTING)).thenAnswer(call -> Optional.ofNullable(row));
        when(fills.findWithLockByUserIdAndJobPostingId(USER, POSTING)).thenAnswer(call -> Optional.ofNullable(row));
        // DB 의 시작 쿼리처럼 찾는 중이 아니거나 시작한 지 오래된 줄만 새로 시작한다
        when(fills.start(eq(USER), eq(POSTING), anyString(), any(), any())).thenAnswer(call -> {
            Instant stuckBefore = call.getArgument(4);
            if (row != null && row.getStatus() == GapFillStatus.RUNNING && row.getRequestedAt().isAfter(stuckBefore)) {
                return 0;
            }
            row = row(GapFillStatus.RUNNING, call.getArgument(3), MAPPER.readValue((String) call.getArgument(2),
                    CONDITIONS), null);
            return 1;
        });
        // DB 의 다시 찾기 쿼리처럼 읽은 그 끝난 줄에서만 시작하고 지문은 그대로 둔다
        when(fills.restart(eq(USER), eq(POSTING), anyString(), any(), any())).thenAnswer(call -> {
            if (row == null || row.getStatus() != GapFillStatus.DONE || !row.getRequestedAt().equals(call.getArgument(4))) {
                return 0;
            }
            row = row(GapFillStatus.RUNNING, call.getArgument(3), MAPPER.readValue((String) call.getArgument(2),
                    CONDITIONS), row.getFingerprint());
            return 1;
        });
        doAnswer(call -> {
            row = row(GapFillStatus.DONE, NOW, List.of(), null);
            return null;
        }).when(fills).finishEmpty(eq(USER), eq(POSTING), any(), any());

    }

    @Test
    @DisplayName("못 채운 조건이 없으면 AI 도 호출 한도도 쓰지 않고 빈 조건으로 DONE 을 저장한다")
    void finishesWithoutAiWhenNothingIsMissing() {

        condition(CriterionType.REQUIRED, null, MatchConditionStatus.MET);
        condition(CriterionType.PREFERRED, null, MatchConditionStatus.MET);
        condition(CriterionType.REQUIRED, GeneralConditionType.LANGUAGE, MatchConditionStatus.CHECK);

        service.start(POSTING_ID, USER, LOGIN);

        verify(fills).finishEmpty(USER, POSTING, NOW, NOW.minus(MatchGapFill.STUCK_AFTER));
        assertThat(service.of(POSTING_ID, USER)).contains(new GapFillView(GapFillStatus.DONE, null, List.of()));
        assertThat(started).isEmpty();
        verify(port, never()).fill(any(), any(), any());
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());

    }

    @Test
    @DisplayName("못 채운 필수 조건만 공고 순서대로 5개까지 보내고, 못 채운 우대 조건과 학력 같은 일반 조건은 보내지 않는다")
    void sendsOnlyMissingRequiredConditionsUpToFive() {

        List<UUID> required = new ArrayList<>();
        condition(CriterionType.REQUIRED, GeneralConditionType.EDUCATION, MatchConditionStatus.NOT_MET);
        condition(CriterionType.REQUIRED, null, MatchConditionStatus.MET);
        for (int i = 0; i < 6; i++) {
            required.add(condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET));
        }
        condition(CriterionType.PREFERRED, null, MatchConditionStatus.NOT_MET);

        assertThat(sentConditionIds()).containsExactlyElementsOf(required.subList(0, 5));

    }

    @Test
    @DisplayName("필수 조건을 모두 채운 공고는 못 채운 우대 조건을 보낸다")
    void sendsMissingPreferredConditionsWhenEveryRequiredIsMet() {

        condition(CriterionType.REQUIRED, null, MatchConditionStatus.MET);
        UUID preferred = condition(CriterionType.PREFERRED, null, MatchConditionStatus.NOT_MET);
        condition(CriterionType.RESPONSIBILITY, null, null);

        assertThat(sentConditionIds()).containsExactly(preferred);

    }

    @Test
    @DisplayName("같은 자료로 이미 찾은 공고는 AI 도 호출 한도도 쓰지 않고, 카드·후보·레포가 바뀌면 다시 찾는다")
    void reusesTheStoredResultUntilTheMaterialsChange() {

        UUID gap = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        confirmedCard(11L, "결제 API");
        repo(7L, "grow22", "pay", true);
        answer(List.of(notFound(gap)));
        service.start(POSTING_ID, USER, LOGIN);
        started.getFirst().run();

        service.start(POSTING_ID, USER, LOGIN);
        assertThat(started).hasSize(1);
        verify(rateLimiter, times(1)).acquire(RateLimitedAction.MATCH_GAP_FILL, USER, 1);

        unused.add(new UnusedCandidate(7L, UUID.randomUUID(), "주문 큐", "이유", List.of()));
        service.start(POSTING_ID, USER, LOGIN);
        assertThat(started).hasSize(2);
        verify(rateLimiter, times(2)).acquire(RateLimitedAction.MATCH_GAP_FILL, USER, 1);

    }

    @Test
    @DisplayName("찾는 중인 공고는 다시 시작하지 않고 한도도 세지 않으며, 시작한 지 5분이 지난 찾기는 실패로 보이고 다시 시작할 수 있다")
    void startsOnceWhileRunningAndRestartsWhenStuck() {

        condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        service.start(POSTING_ID, USER, LOGIN);
        service.start(POSTING_ID, USER, LOGIN);
        assertThat(started).hasSize(1);
        verify(rateLimiter, times(1)).acquire(RateLimitedAction.MATCH_GAP_FILL, USER, 1);

        ReflectionTestUtils.setField(row, "requestedAt", NOW.minus(MatchGapFill.STUCK_AFTER));
        assertThat(service.of(POSTING_ID, USER).orElseThrow().state()).isEqualTo(GapFillStatus.FAILED);
        assertThat(service.of(POSTING_ID, USER).orElseThrow().pollAfterMs()).isNull();

        service.start(POSTING_ID, USER, LOGIN);
        assertThat(started).hasSize(2);
        verify(rateLimiter, times(2)).acquire(RateLimitedAction.MATCH_GAP_FILL, USER, 1);

    }

    @Test
    @DisplayName("찾는 동안 조회는 AI 가 보낸 조건 상태를 공고 조건 순서로 pollAfterMs 와 함께 보이고, 끝나면 저장한 결론에 pollAfterMs 는 null 이며 메모리 진행을 지운다")
    void showsLiveConditionsWhileRunningAndStoredOnesAfter() {

        UUID first = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        UUID second = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        List<GapFillView> seenWhileRunning = new ArrayList<>();
        GapFillCondition searching = new GapFillCondition(second, GapFillDecision.SEARCH, "관련 있음", false,
                List.of(new GapFillStep(GapFillStepKind.CANDIDATES_CHECKED, 3, null, null)), null, null);
        when(port.fill(eq(USER), any(), any())).thenAnswer(call -> {
            Consumer<GapFillCondition> onCondition = call.getArgument(2);
            onCondition.accept(searching);
            seenWhileRunning.add(service.of(POSTING_ID, USER).orElseThrow());
            return List.of(notFound(first), notFound(second));
        });

        service.start(POSTING_ID, USER, LOGIN);
        assertThat(service.of(POSTING_ID, USER)).contains(new GapFillView(GapFillStatus.RUNNING,
                GapFillService.POLL_AFTER_MS, List.of(GapFillCondition.waiting(first), GapFillCondition.waiting(second))));
        started.getFirst().run();

        assertThat(seenWhileRunning).containsExactly(new GapFillView(GapFillStatus.RUNNING,
                GapFillService.POLL_AFTER_MS, List.of(GapFillCondition.waiting(first), searching)));
        assertThat(service.of(POSTING_ID, USER)).contains(new GapFillView(GapFillStatus.DONE, null,
                List.of(notFound(first), notFound(second))));
        assertThat(liveLog.of(USER, POSTING)).isEmpty();

    }

    @Test
    @DisplayName("AI 가 실패하면 지문 없이 실패로 저장하고 메모리 진행을 지운다")
    void savesFailureAndDropsLiveWhenAiFails() {

        condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        when(port.fill(eq(USER), any(), any())).thenThrow(new MatchingAiUnavailableException("실패"));

        service.start(POSTING_ID, USER, LOGIN);
        started.getFirst().run();

        assertThat(row.getStatus()).isEqualTo(GapFillStatus.FAILED);
        assertThat(row.getFingerprint()).isNull();
        assertThat(row.getFinishedAt()).isEqualTo(NOW);
        assertThat(liveLog.of(USER, POSTING)).isEmpty();

    }

    @Test
    @DisplayName("제안의 title·repoName·repoId 는 보낸 자료에서 채워, 후보는 후보 제목과 그 레포 이름·id, 작성 중 카드는 카드 제목, 레포 분석은 레포 이름·id 다")
    void fillsProposalTitlesFromTheSentMaterials() {

        UUID first = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        UUID second = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        UUID third = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        UUID pay = repo(7L, "grow22", "pay", true);
        UUID repo = repo(8L, "grow22", "shop", false);
        UUID candidate = UUID.randomUUID();
        unused.add(new UnusedCandidate(7L, candidate, "주문 큐 만들기", "이유", List.of("feat: 큐")));
        UUID draft = draftCard(21L, "캐시 도입");
        answer(List.of(proposed(first, GapFillAction.CREATE_CARD, candidate),
                proposed(second, GapFillAction.CONTINUE_CARD, draft),
                proposed(third, GapFillAction.ANALYZE_REPO, repo)));

        service.start(POSTING_ID, USER, LOGIN);
        started.getFirst().run();

        assertThat(row.getConditions()).extracting(GapFillCondition::proposal).containsExactly(
                new GapFillProposal(GapFillAction.CREATE_CARD, candidate, "주문 큐 만들기", "grow22/pay", pay, "이유"),
                new GapFillProposal(GapFillAction.CONTINUE_CARD, draft, "캐시 도입", null, null, "이유"),
                new GapFillProposal(GapFillAction.ANALYZE_REPO, repo, "grow22/shop", "grow22/shop", repo, "이유"));

    }

    @Test
    @DisplayName("멈춘 것으로 보여 다시 시작한 뒤 늦게 끝난 앞 찾기는 새 찾기의 줄을 바꾸지 않는다")
    void lateRunDoesNotOverwriteARestartedOne() {

        UUID gap = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        answer(List.of(notFound(gap)));
        service.start(POSTING_ID, USER, LOGIN);
        row = row(GapFillStatus.RUNNING, NOW.plus(MatchGapFill.STUCK_AFTER), List.of(GapFillCondition.waiting(gap)),
                null);

        started.getFirst().run();

        assertThat(row.getStatus()).isEqualTo(GapFillStatus.RUNNING);
        assertThat(row.getConditions()).containsExactly(GapFillCondition.waiting(gap));

    }

    @Test
    @DisplayName("확정 카드가 40장보다 많으면 최근에 만든 40장을 보낸다")
    void sendsTheNewestConfirmedCardsWhenThereAreTooMany() {

        condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        for (long id = 1; id <= GapFillService.MAX_CARDS + 1; id++) {
            confirmedCard(id, "카드 " + id);
        }
        answer(List.of());

        service.start(POSTING_ID, USER, LOGIN);
        started.getFirst().run();

        ArgumentCaptor<GapFillRequest> sent = ArgumentCaptor.forClass(GapFillRequest.class);
        verify(port).fill(eq(USER), sent.capture(), any());
        assertThat(sent.getValue().cards()).extracting(GapFillRequest.Card::title)
                .hasSize(GapFillService.MAX_CARDS)
                .doesNotContain("카드 1")
                .contains("카드 41");

    }

    @Test
    @DisplayName("끝난 채우기 결과가 없으면(처음·찾는 중·실패) 다시 찾기를 거절하고 AI 도 호출 한도도 쓰지 않는다")
    void forcingNeedsAFinishedResult() {

        UUID gap = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        List<MatchGapFill> unfinished = Arrays.asList(null,
                row(GapFillStatus.RUNNING, NOW, List.of(skipped(gap)), "f"),
                row(GapFillStatus.FAILED, NOW, List.of(skipped(gap)), null));

        for (MatchGapFill each : unfinished) {
            row = each;
            assertThatThrownBy(() -> service.force(POSTING_ID, USER, LOGIN, List.of(gap)))
                    .isInstanceOf(GapFillNotFinishedException.class);
        }

        assertThat(started).isEmpty();
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());

    }

    @Test
    @DisplayName("'찾지 않음' 으로 멈춘 조건이 아니거나 지금은 채운 조건이거나 보내지 않은 조건이면 다시 찾기를 거절한다")
    void forcingTakesOnlySkippedConditionsThatAreStillMissing() {

        UUID searched = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        UUID metNow = condition(CriterionType.REQUIRED, null, MatchConditionStatus.MET);
        UUID skippedGap = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        row = row(GapFillStatus.DONE, NOW.minusSeconds(60),
                List.of(notFound(searched), skipped(metNow), skipped(skippedGap)), "f");

        for (List<UUID> wrong : List.of(List.of(searched), List.of(metNow), List.of(skippedGap, UUID.randomUUID()))) {
            assertThatThrownBy(() -> service.force(POSTING_ID, USER, LOGIN, wrong))
                    .isInstanceOf(GapFillNotSkippedException.class);
        }

        assertThat(started).isEmpty();
        verify(rateLimiter, never()).acquire(any(), any(), anyInt());

    }

    @Test
    @DisplayName("다시 찾기는 고른 조건만 첫 판단과 상관없이 찾게 보내고, 찾는 동안과 끝난 뒤 나머지 조건은 앞 결과 그대로이며 지문은 바뀌지 않는다")
    void forcingSearchesOnlyChosenConditionsAndKeepsTheRest() {

        UUID kept = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        UUID chosen = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        GapFillCondition keptResult = skipped(kept);
        row = row(GapFillStatus.DONE, NOW.minusSeconds(60), List.of(keptResult, skipped(chosen)), "f".repeat(64));
        GapFillCondition searchedAnyway = new GapFillCondition(chosen, GapFillDecision.SKIP, "분야가 다르다", true,
                List.of(), null, GapFillResult.NOT_FOUND);
        List<GapFillView> seenWhileRunning = new ArrayList<>();
        when(port.fill(eq(USER), any(), any())).thenAnswer(call -> {
            seenWhileRunning.add(service.of(POSTING_ID, USER).orElseThrow());
            return List.of(searchedAnyway);
        });

        service.force(POSTING_ID, USER, LOGIN, List.of(chosen));
        started.getFirst().run();

        ArgumentCaptor<GapFillRequest> sent = ArgumentCaptor.forClass(GapFillRequest.class);
        verify(port).fill(eq(USER), sent.capture(), any());
        assertThat(sent.getValue().conditions()).extracting(GapFillRequest.Condition::conditionId).containsExactly(chosen);
        assertThat(sent.getValue().forceConditionIds()).containsExactly(chosen);
        assertThat(seenWhileRunning.getFirst().conditions()).containsExactly(keptResult, GapFillCondition.waiting(chosen));
        assertThat(row.getStatus()).isEqualTo(GapFillStatus.DONE);
        assertThat(row.getConditions()).containsExactly(keptResult, searchedAnyway);
        assertThat(row.getFingerprint()).isEqualTo("f".repeat(64));
        verify(rateLimiter).acquire(RateLimitedAction.MATCH_GAP_FILL, USER, 1);

    }

    @Test
    @DisplayName("GitHub 연결은 새로 찾거나 다시 찾을 때만 먼저 보고, 없으면 시작하지 않고 호출 한도도 쓰지 않으며, 같은 자료로 이미 찾은 결과는 연결 없이 그대로 보인다")
    void checksGithubOnlyBeforeANewSearch() {

        UUID gap = condition(CriterionType.REQUIRED, null, MatchConditionStatus.NOT_MET);
        answer(List.of(skipped(gap)));
        service.start(POSTING_ID, USER, LOGIN);
        started.getFirst().run();
        doThrow(new GithubNotConnectedException()).when(githubAccess).requireUsableConnection(USER);

        service.start(POSTING_ID, USER, LOGIN);
        assertThatThrownBy(() -> service.force(POSTING_ID, USER, LOGIN, List.of(gap)))
                .isInstanceOf(GithubNotConnectedException.class);
        confirmedCard(11L, "새 카드");
        assertThatThrownBy(() -> service.start(POSTING_ID, USER, LOGIN))
                .isInstanceOf(GithubNotConnectedException.class);

        assertThat(started).hasSize(1);
        assertThat(row.getStatus()).isEqualTo(GapFillStatus.DONE);
        verify(rateLimiter, times(1)).acquire(RateLimitedAction.MATCH_GAP_FILL, USER, 1);

    }

    private List<UUID> sentConditionIds() {

        answer(List.of());
        service.start(POSTING_ID, USER, LOGIN);
        started.getFirst().run();
        ArgumentCaptor<GapFillRequest> sent = ArgumentCaptor.forClass(GapFillRequest.class);
        verify(port).fill(eq(USER), sent.capture(), any());
        return sent.getValue().conditions().stream().map(GapFillRequest.Condition::conditionId).toList();

    }

    private void answer(List<GapFillCondition> result) {

        when(port.fill(eq(USER), any(), any())).thenReturn(result);

    }

    private UUID condition(CriterionType type, GeneralConditionType general, MatchConditionStatus status) {

        UUID id = UUID.randomUUID();
        conditions.add(new MatchCondition(id, type, "조건 " + conditions.size(), "인용", general, status, List.of()));
        return id;

    }

    private void confirmedCard(Long id, String title) {

        confirmed.add(card(id, title));
        labels.put(id, new MatchingCardLabel(UUID.randomUUID(), title, CardKind.TECH, null));

    }

    private UUID draftCard(Long id, String title) {

        drafts.add(card(id, "가린 " + title));
        UUID publicId = UUID.randomUUID();
        labels.put(id, new MatchingCardLabel(publicId, title, CardKind.TECH, null));
        return publicId;

    }

    private UUID repo(Long id, String owner, String name, boolean analyzed) {

        UUID publicId = UUID.randomUUID();
        repos.add(new ConnectedRepositoryReference(id, publicId, owner, name, analyzed ? NOW : null));
        return publicId;

    }

    private static MatchingCard card(Long id, String title) {

        return new MatchingCard(id, null, title, null, null, "행동", null, List.of(CardTech.SPRING));

    }

    private static GapFillCondition notFound(UUID id) {

        return new GapFillCondition(id, GapFillDecision.SEARCH, "관련 있음", false, List.of(), null, GapFillResult.NOT_FOUND);

    }

    private static GapFillCondition skipped(UUID id) {

        return new GapFillCondition(id, GapFillDecision.SKIP, "분야가 다르다", false, List.of(), null, GapFillResult.SKIPPED);

    }

    private static GapFillCondition proposed(UUID id, GapFillAction action, UUID target) {

        return new GapFillCondition(id, GapFillDecision.SEARCH, "관련 있음", false, List.of(),
                new GapFillProposal(action, target, null, null, null, "이유"), GapFillResult.PROPOSED);

    }

    private static MatchTarget target() {

        return new MatchTarget(POSTING_ID, "나래랩스", "narae", null, "서버 개발자", JobCategory.BACKEND, null,
                new MatchCount(1, 0), new MatchCount(0, 0), ExperienceLevel.ENTRY, null,
                "https://careers.example.com/1", NOW, null, List.of(), null);

    }

    private static MatchGapFill row(GapFillStatus status, Instant requestedAt, List<GapFillCondition> conditions,
                                    String fingerprint) {

        MatchGapFill fill = new MatchGapFill();
        ReflectionTestUtils.setField(fill, "userId", USER);
        ReflectionTestUtils.setField(fill, "jobPostingId", POSTING);
        ReflectionTestUtils.setField(fill, "status", status);
        ReflectionTestUtils.setField(fill, "conditions", conditions);
        ReflectionTestUtils.setField(fill, "fingerprint", fingerprint);
        ReflectionTestUtils.setField(fill, "requestedAt", requestedAt);
        return fill;

    }
}
