package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AssistantTool;
import com.gitory.backend.assistant.port.RepoAnalysisPort;
import com.gitory.backend.assistant.port.RepoNotFoundException;
import com.gitory.backend.card.domain.MeCardStatsService;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.coverletter.domain.CoverLetterPostingNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterService;
import com.gitory.backend.coverletter.domain.CoverLetterStage;
import com.gitory.backend.coverletter.domain.CoverLetterView;
import com.gitory.backend.coverletter.domain.InvalidCoverLetterInputException;
import com.gitory.backend.coverletter.domain.NewQuestion;
import com.gitory.backend.coverletter.domain.NoConfirmedCardsException;
import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.GeneralConditionType;
import com.gitory.backend.matching.domain.JobCategory;
import com.gitory.backend.matching.domain.MatchCondition;
import com.gitory.backend.matching.domain.MatchConditionStatus;
import com.gitory.backend.matching.domain.MatchDetail;
import com.gitory.backend.matching.domain.MatchDetailService;
import com.gitory.backend.matching.domain.MatchListService;
import com.gitory.backend.matching.domain.MatchPostingNotFoundException;
import com.gitory.backend.matching.domain.MatchSummary;
import com.gitory.backend.matching.domain.MatchTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AssistantToolsTest {

    private static final Long USER = 1L;
    private static final UUID RUN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID POSTING = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID REPO = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    // 서울에서는 2026-11-06 낮 12시다
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final MatchListService matchList = mock(MatchListService.class);
    private final MatchDetailService matchDetails = mock(MatchDetailService.class);
    private final CoverLetterService coverLetters = mock(CoverLetterService.class);
    private final MeCardStatsService cardStats = mock(MeCardStatsService.class);
    private final RepoAnalysisPort repoAnalysis = mock(RepoAnalysisPort.class);
    private final AssistantTools tools = new AssistantTools(matchList, matchDetails, coverLetters, cardStats,
            repoAnalysis, CLOCK);

    @Test
    @DisplayName("이미 맞춰 본 공고만 필수 조건 수를 싣는다")
    void fillsRequiredCountsOnlyForAlreadyJudgedPostings() {

        MatchTarget judged = target(UUID.randomUUID(), "토스", "백엔드 개발자", NOW.plusSeconds(2 * 86_400));
        MatchTarget unjudged = target(UUID.randomUUID(), "카카오", "서버 개발자", null);
        given(cardStats.confirmedCards(USER)).willReturn(2);
        given(matchList.list(null)).willReturn(List.of(judged, unjudged));
        given(matchDetails.detail(judged.id(), USER)).willReturn(detail(judged, false, new MatchSummary(3, 4), List.of()));
        given(matchDetails.detail(unjudged.id(), USER)).willReturn(detail(unjudged, true, null, List.of()));

        ToolResult result = tools.execute(USER, 2, findCall());

        assertThat(result.ok()).isTrue();
        assertThat(result.totalFound()).isEqualTo(2);
        assertThat(result.postings()).extracting(RunItem.Posting::postingId, RunItem.Posting::company,
                        RunItem.Posting::title, RunItem.Posting::requiredMet, RunItem.Posting::requiredTotal)
                .containsExactly(tuple(judged.id(), "토스", "백엔드 개발자", 3, 4),
                        tuple(unjudged.id(), "카카오", "서버 개발자", null, null));
        verify(matchDetails, never()).judge(any(), any());

    }

    @Test
    @DisplayName("확정 카드가 없으면 저장된 판단을 읽지 않아 필수 조건 수를 싣지 않는다")
    void leavesRequiredCountsEmptyWithoutConfirmedCards() {

        MatchTarget target = target(UUID.randomUUID(), "토스", "백엔드 개발자", null);
        given(cardStats.confirmedCards(USER)).willReturn(0);
        given(matchList.list(null)).willReturn(List.of(target));

        ToolResult result = tools.execute(USER, 2, findCall());

        assertThat(result.postings()).extracting(RunItem.Posting::requiredMet, RunItem.Posting::requiredTotal)
                .containsExactly(tuple(null, null));
        verifyNoInteractions(matchDetails);

    }

    @Test
    @DisplayName("남은 날은 서울 날짜로 세고, 지났으면 0 이며, 마감이 없으면 null 이다")
    void countsDaysLeftBySeoulDate() {

        MatchTarget past = target(UUID.randomUUID(), "A", "개발자", Instant.parse("2026-11-04T00:00:00Z"));
        MatchTarget endOfToday = target(UUID.randomUUID(), "B", "개발자", Instant.parse("2026-11-06T14:59:00Z"));
        MatchTarget startOfTomorrow = target(UUID.randomUUID(), "C", "개발자", Instant.parse("2026-11-06T15:00:00Z"));
        MatchTarget undated = target(UUID.randomUUID(), "D", "개발자", null);
        given(matchList.list(null)).willReturn(List.of(undated, startOfTomorrow, endOfToday, past));

        ToolResult result = tools.execute(USER, 1, findCall());

        assertThat(result.postings()).extracting(RunItem.Posting::company, RunItem.Posting::daysLeft)
                .containsExactly(tuple("A", 0), tuple("B", 0), tuple("C", 1), tuple("D", null));

    }

    @Test
    @DisplayName("맞춰 보지 않은 공고는 AI 로 맞춰 보고, 맞은 조건과 모자란 조건을 다섯 개까지 남긴다")
    void judgesUnjudgedPostingAndKeepsFiveConditionsEach() {

        MatchTarget target = target(POSTING, "토스", "백엔드 개발자", NOW.plusSeconds(2 * 86_400));
        RunItem.Posting posting = new RunItem.Posting("p1", POSTING, "토스", "백엔드 개발자", 2, null, null);
        List<MatchCondition> conditions = new ArrayList<>();
        conditions.add(condition(CriterionType.REQUIRED, "가".repeat(100), null, MatchConditionStatus.MET));
        IntStream.rangeClosed(2, 7).forEach(number ->
                conditions.add(condition(CriterionType.REQUIRED, "충족 " + number, null, MatchConditionStatus.MET)));
        IntStream.rangeClosed(1, 6).forEach(number ->
                conditions.add(condition(CriterionType.REQUIRED, "부족 " + number, null, MatchConditionStatus.NOT_MET)));
        conditions.add(condition(CriterionType.PREFERRED, "우대", null, MatchConditionStatus.MET));
        conditions.add(condition(CriterionType.REQUIRED, "학력", GeneralConditionType.EDUCATION, MatchConditionStatus.MET));
        conditions.add(condition(CriterionType.RESPONSIBILITY, "담당 업무", null, null));
        given(cardStats.confirmedCards(USER)).willReturn(1);
        given(matchDetails.detail(POSTING, USER)).willReturn(detail(target, true, null, List.of()));
        given(matchDetails.judge(POSTING, USER)).willReturn(detail(target, false, new MatchSummary(7, 13), conditions));

        ToolResult result = tools.execute(USER, 3, matchCall(posting));

        assertThat(result.ok()).isTrue();
        assertThat(result.match().requiredMet()).isEqualTo(7);
        assertThat(result.match().requiredTotal()).isEqualTo(13);
        assertThat(result.match().met()).containsExactly("가".repeat(80), "충족 2", "충족 3", "충족 4", "충족 5");
        assertThat(result.match().missing()).containsExactly("부족 1", "부족 2", "부족 3", "부족 4", "부족 5");
        assertThat(result.posting()).isEqualTo(posting.withRequired(7, 13));

    }

    @Test
    @DisplayName("확정 카드가 없으면 맞춰 보지 않고 확정 카드 없음으로 남긴다")
    void failsWithoutConfirmedCardsBeforeMatching() {

        given(cardStats.confirmedCards(USER)).willReturn(0);

        ToolResult result = tools.execute(USER, 1, matchCall(new RunItem.Posting("p1", POSTING, "토스", "백엔드", 2, null, null)));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(ToolError.NO_CONFIRMED_CARDS);
        verifyNoInteractions(matchDetails);

    }

    @Test
    @DisplayName("자소서는 공고가 있으면 공고 제목을, 없으면 AI 가 준 직무를 지원 직무로 만들고 걸음마다 다른 요청 키를 쓴다")
    void startsCoverLetterWithPostingTitleOrGivenRoleAndStepKey() {

        UUID letterId = UUID.randomUUID();
        List<NewQuestion> questions = List.of(new NewQuestion("지원 동기를 쓰세요", null), new NewQuestion("협업 경험을 쓰세요", 700));
        RunItem.Posting posting = new RunItem.Posting("p1", POSTING, "토스", "가".repeat(120), 2, null, null);
        given(coverLetters.create(any(), any(), any(), any(), any(), any(), any())).willReturn(letterView(letterId));

        ToolResult withPosting = tools.execute(USER, 3, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null,
                "p1", posting, null, null, null, questions));
        ToolResult withoutPosting = tools.execute(USER, 5, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null,
                null, null, null, null, "백엔드 개발자", questions));

        assertThat(withPosting.coverLetterId()).isEqualTo(letterId);
        assertThat(withPosting.questionCount()).isEqualTo(2);
        assertThat(withoutPosting.ok()).isTrue();
        verify(coverLetters).create(USER, "가".repeat(100), null, List.of(), questions, POSTING,
                "assistant-" + RUN + "-3");
        verify(coverLetters).create(USER, "백엔드 개발자", null, List.of(), questions, null, "assistant-" + RUN + "-5");

    }

    @Test
    @DisplayName("자소서의 지원 직무를 공고에서도 AI 에게서도 알 수 없으면 만들지 않고 실패로 남긴다")
    void failsCoverLetterWithoutAnyJobRole() {

        ToolResult result = tools.execute(USER, 3, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null, null,
                null, null, null, null, List.of(new NewQuestion("지원 동기를 쓰세요", null))));

        assertThat(result.error()).isEqualTo(ToolError.FAILED);
        verifyNoInteractions(coverLetters);

    }

    @Test
    @DisplayName("레포 정리를 시작하고 레포와 Job 의 공개 id 를 남긴다")
    void startsRepoAnalysis() {

        UUID jobId = UUID.randomUUID();
        given(repoAnalysis.start(USER, REPO)).willReturn(jobId);

        ToolResult result = tools.execute(USER, 2, callOf(AssistantTool.START_REPO_ANALYSIS));

        assertThat(result.ok()).isTrue();
        assertThat(result.repoId()).isEqualTo(REPO);
        assertThat(result.jobId()).isEqualTo(jobId);

    }

    @ParameterizedTest
    @MethodSource("failures")
    @DisplayName("도구가 던진 예외는 걸음 실패 코드로 바꾼다")
    void turnsToolExceptionsIntoStepErrors(AssistantTool tool, RuntimeException thrown, ToolError expected) {

        given(cardStats.confirmedCards(USER)).willReturn(1);
        given(matchList.list(null)).willThrow(thrown);
        given(matchDetails.detail(any(), any())).willThrow(thrown);
        given(coverLetters.create(any(), any(), any(), any(), any(), any(), any())).willThrow(thrown);
        given(repoAnalysis.start(any(), any())).willThrow(thrown);

        ToolResult result = tools.execute(USER, 1, callOf(tool));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(expected);

    }

    static Stream<Arguments> failures() {

        return Stream.of(
                Arguments.of(AssistantTool.FIND_POSTINGS, new IllegalStateException("x"), ToolError.FAILED),
                Arguments.of(AssistantTool.MATCH_POSTING, new MatchPostingNotFoundException(), ToolError.NOT_FOUND),
                Arguments.of(AssistantTool.MATCH_POSTING, new RateLimitExceededException(30), ToolError.RATE_LIMITED),
                Arguments.of(AssistantTool.START_COVER_LETTER, new NoConfirmedCardsException(),
                        ToolError.NO_CONFIRMED_CARDS),
                Arguments.of(AssistantTool.START_COVER_LETTER, new CoverLetterPostingNotFoundException(),
                        ToolError.NOT_FOUND),
                Arguments.of(AssistantTool.START_COVER_LETTER, new RateLimitExceededException(30),
                        ToolError.RATE_LIMITED),
                Arguments.of(AssistantTool.START_COVER_LETTER, new InvalidCoverLetterInputException("x"),
                        ToolError.FAILED),
                Arguments.of(AssistantTool.START_REPO_ANALYSIS, new RepoNotFoundException(), ToolError.NOT_FOUND),
                Arguments.of(AssistantTool.START_REPO_ANALYSIS, new IllegalStateException("x"), ToolError.FAILED));

    }

    private static ToolCall callOf(AssistantTool tool) {

        return switch (tool) {
            case FIND_POSTINGS -> findCall();
            case MATCH_POSTING -> matchCall(new RunItem.Posting("p1", POSTING, "토스", "백엔드", 2, null, null));
            case START_COVER_LETTER -> new ToolCall(RUN, tool, null, null, null, null, null, "백엔드 개발자",
                    List.of(new NewQuestion("지원 동기를 쓰세요", null)));
            case START_REPO_ANALYSIS -> new ToolCall(RUN, tool, null, null, null, "r1",
                    new RepoLabel("r1", REPO, "gitory"), null, List.of());
        };

    }

    private static ToolCall findCall() {

        return new ToolCall(RUN, AssistantTool.FIND_POSTINGS, new RunItem.Find(null, List.of()), null, null, null,
                null, null, List.of());

    }

    private static ToolCall matchCall(RunItem.Posting posting) {

        return new ToolCall(RUN, AssistantTool.MATCH_POSTING, null, "p1", posting, null, null, null, List.of());

    }

    private static MatchTarget target(UUID id, String company, String role, Instant expiresAt) {

        return new MatchTarget(id, company, company, null, role, JobCategory.BACKEND, null, null, null,
                ExperienceLevel.ENTRY, null, null, null, expiresAt, List.of(), null);

    }

    private static MatchDetail detail(MatchTarget target, boolean needsJudgement, MatchSummary summary,
                                      List<MatchCondition> conditions) {

        return new MatchDetail(target, null, null, needsJudgement, summary, conditions);

    }

    private static MatchCondition condition(CriterionType type, String text, GeneralConditionType generalType,
                                            MatchConditionStatus status) {

        return new MatchCondition(UUID.randomUUID(), type, text, null, generalType, status, List.of());

    }

    private static CoverLetterView letterView(UUID id) {

        return new CoverLetterView(id, CoverLetterStage.ASSIGNING, null, null, null, true, "백엔드", null, List.of(),
                null, null, null, null, List.of(), List.of(), List.of());

    }
}
