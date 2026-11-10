package com.gitory.backend.assistant.domain;

import com.gitory.backend.agent.port.AssistantTool;
import com.gitory.backend.assistant.port.RepoAnalysisPort;
import com.gitory.backend.assistant.port.RepoNotFoundException;
import com.gitory.backend.card.domain.CardNotFoundException;
import com.gitory.backend.card.domain.MeCardStatsService;
import com.gitory.backend.card.domain.StarField;
import com.gitory.backend.card.domain.interview.AnswerMode;
import com.gitory.backend.card.domain.interview.InterviewAskService;
import com.gitory.backend.card.domain.interview.InterviewCapReachedException;
import com.gitory.backend.card.domain.interview.InterviewSourceType;
import com.gitory.backend.card.domain.interview.InterviewTurnView;
import com.gitory.backend.card.domain.interview.QuestionType;
import com.gitory.backend.card.domain.interview.TriggerSource;
import com.gitory.backend.common.infra.ratelimit.RateLimitExceededException;
import com.gitory.backend.consent.domain.GithubNotConnectedException;
import com.gitory.backend.consent.domain.UserLoginService;
import com.gitory.backend.coverletter.domain.CoverLetterNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterPolishService;
import com.gitory.backend.coverletter.domain.CoverLetterPostingNotFoundException;
import com.gitory.backend.coverletter.domain.CoverLetterQueryService;
import com.gitory.backend.coverletter.domain.CoverLetterService;
import com.gitory.backend.coverletter.domain.CoverLetterStage;
import com.gitory.backend.coverletter.domain.CoverLetterView;
import com.gitory.backend.coverletter.domain.InvalidCoverLetterInputException;
import com.gitory.backend.coverletter.domain.NewQuestion;
import com.gitory.backend.coverletter.domain.NoConfirmedCardsException;
import com.gitory.backend.coverletter.domain.PolishPoint;
import com.gitory.backend.coverletter.domain.QuestionPolish;
import com.gitory.backend.matching.domain.CriterionType;
import com.gitory.backend.matching.domain.ExperienceLevel;
import com.gitory.backend.matching.domain.GapFillNeedsJudgementException;
import com.gitory.backend.matching.domain.GapFillService;
import com.gitory.backend.matching.domain.GapFillCondition;
import com.gitory.backend.matching.domain.GapFillStatus;
import com.gitory.backend.matching.domain.GapFillResult;
import com.gitory.backend.matching.domain.GapFillView;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AssistantToolsTest {

    private static final Long USER = 1L;
    private static final UUID RUN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID POSTING = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID REPO = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID CARD = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID LETTER = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    // 서울에서는 2026-11-06 낮 12시다
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final MatchListService matchList = mock(MatchListService.class);
    private final MatchDetailService matchDetails = mock(MatchDetailService.class);
    private final CoverLetterService coverLetters = mock(CoverLetterService.class);
    private final MeCardStatsService cardStats = mock(MeCardStatsService.class);
    private final RepoAnalysisPort repoAnalysis = mock(RepoAnalysisPort.class);
    private final GapFillService gapFills = mock(GapFillService.class);
    private final UserLoginService users = mock(UserLoginService.class);
    private final InterviewAskService interviews = mock(InterviewAskService.class);
    private final CoverLetterQueryService letterReader = mock(CoverLetterQueryService.class);
    private final CoverLetterPolishService polishes = mock(CoverLetterPolishService.class);
    private final List<Long> pauses = new ArrayList<>();
    private final AssistantTools tools = new AssistantTools(matchList, matchDetails, coverLetters, cardStats,
            repoAnalysis, gapFills, users, interviews, letterReader, polishes, pauses::add, CLOCK);

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
        given(coverLetters.create(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).willReturn(letterView(letterId));

        ToolResult withPosting = tools.execute(USER, 3, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null,
                "p1", posting, null, null, null, questions, null, null, null, null));
        ToolResult withoutPosting = tools.execute(USER, 5, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null,
                null, null, null, null, "백엔드 개발자", questions, null, null, null, null));

        assertThat(withPosting.coverLetterId()).isEqualTo(letterId);
        assertThat(withPosting.questionCount()).isEqualTo(2);
        assertThat(withoutPosting.ok()).isTrue();
        verify(coverLetters).create(USER, "가".repeat(100), null, List.of(), questions, POSTING,
                "assistant-" + RUN + "-3", false);
        verify(coverLetters).create(USER, "백엔드 개발자", null, List.of(), questions, null, "assistant-" + RUN + "-5",
                false);

    }

    @Test
    @DisplayName("기본 문항이면 자주 나오는 문항 3개를 순서대로 넣어 만들고 결과에 기본 문항 표시와 문항 수 3 을 남긴다")
    void startsCoverLetterWithDefaultQuestions() {

        UUID letterId = UUID.randomUUID();
        List<NewQuestion> expected = List.of(
                new NewQuestion("지원 동기와 입사 후 포부를 적어 주세요", null),
                new NewQuestion("직무와 관련된 기술로 문제를 해결한 경험을 적어 주세요", null),
                new NewQuestion("팀과 협업하며 성과를 낸 경험을 적어 주세요", null));
        given(coverLetters.create(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .willReturn(letterView(letterId));

        ToolResult result = tools.execute(USER, 3, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null, null,
                null, null, null, "백엔드 개발자", List.of(), null, null, null, null, true));

        assertThat(result.defaultQuestions()).isTrue();
        assertThat(result.questionCount()).isEqualTo(3);
        verify(coverLetters).create(USER, "백엔드 개발자", null, List.of(), expected, null, "assistant-" + RUN + "-3",
                true);

    }

    @Test
    @DisplayName("문항을 직접 줬으면 기본 문항 표시를 남기지 않는다")
    void givenQuestionsLeaveNoDefaultMark() {

        given(coverLetters.create(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .willReturn(letterView(UUID.randomUUID()));

        ToolResult result = tools.execute(USER, 3, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null, null,
                null, null, null, "백엔드 개발자", List.of(new NewQuestion("지원 동기를 쓰세요", null)), null, null,
                null, null));

        assertThat(result.defaultQuestions()).isNull();

    }

    @Test
    @DisplayName("자소서의 지원 직무를 공고에서도 AI 에게서도 알 수 없으면 만들지 않고 실패로 남긴다")
    void failsCoverLetterWithoutAnyJobRole() {

        ToolResult result = tools.execute(USER, 3, new ToolCall(RUN, AssistantTool.START_COVER_LETTER, null, null,
                null, null, null, null, List.of(new NewQuestion("지원 동기를 쓰세요", null)), null, null, null, null));

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

    @Test
    @DisplayName("모자란 조건 채우기는 끝날 때까지 상태를 읽으며 기다리고, 근거를 찾은 조건 수와 새 상세를 남긴다")
    void fillsGapsAfterWaitingUntilDone() {

        MatchTarget target = target(POSTING, "토스", "백엔드 개발자", null);
        given(users.loginOf(USER)).willReturn("grow22");
        given(gapFills.start(POSTING, USER, "grow22"))
                .willReturn(detail(target, false, new MatchSummary(1, 3), List.of()));
        given(gapFills.of(POSTING, USER)).willReturn(Optional.of(fillView(GapFillStatus.RUNNING, 1500)),
                Optional.of(fillView(GapFillStatus.RUNNING, 1500)),
                Optional.of(new GapFillView(GapFillStatus.DONE, null, List.of(gapCondition(GapFillResult.PROPOSED),
                        gapCondition(GapFillResult.NOT_FOUND), gapCondition(GapFillResult.PROPOSED)))));
        given(matchDetails.detail(POSTING, USER)).willReturn(detail(target, false, new MatchSummary(1, 3),
                List.of(condition(CriterionType.REQUIRED, "자바 경험", null, MatchConditionStatus.MET),
                        condition(CriterionType.REQUIRED, "도커 경험", null, MatchConditionStatus.MET))));

        ToolResult result = tools.execute(USER, 3, fillCall());

        assertThat(result.ok()).isTrue();
        assertThat(result.gapsFilled()).isEqualTo(2);
        assertThat(result.posting().requiredMet()).isEqualTo(1);
        assertThat(result.match().met()).containsExactly("자바 경험", "도커 경험");
        assertThat(pauses).containsExactly(1500L, 1500L);

    }

    @Test
    @DisplayName("채우기가 90초가 지나도록 끝나지 않으면 기다리기를 멈추고 실패로 남긴다")
    void failsWhenGapFillOutlastsNinetySeconds() {

        MatchTarget target = target(POSTING, "토스", "백엔드 개발자", null);
        given(gapFills.start(any(), any(), any())).willReturn(detail(target, false, new MatchSummary(1, 3), List.of()));
        given(gapFills.of(POSTING, USER)).willReturn(Optional.of(fillView(GapFillStatus.RUNNING, 1500)));

        ToolResult result = tools.execute(USER, 3, fillCall());

        assertThat(result.error()).isEqualTo(ToolError.FAILED);
        assertThat(pauses).hasSize(60).allMatch(pause -> pause == 1500L);
        verify(matchDetails, never()).detail(any(), any());

    }

    @Test
    @DisplayName("채우기가 실패로 끝나면 상세를 다시 읽지 않고 실패로 남긴다")
    void failsWhenGapFillFailed() {

        MatchTarget target = target(POSTING, "토스", "백엔드 개발자", null);
        given(gapFills.start(any(), any(), any())).willReturn(detail(target, false, new MatchSummary(1, 3), List.of()));
        given(gapFills.of(POSTING, USER)).willReturn(Optional.of(fillView(GapFillStatus.FAILED, null)));

        ToolResult result = tools.execute(USER, 3, fillCall());

        assertThat(result.error()).isEqualTo(ToolError.FAILED);
        assertThat(pauses).isEmpty();
        verify(matchDetails, never()).detail(any(), any());

    }

    @Test
    @DisplayName("카드 빈 칸 질문은 연 질문의 칸과 글을 남기고, 더 물을 것이 없으면 질문 없이 성공으로 남긴다")
    void asksCardGapOrReportsNothingToAsk() {

        given(interviews.ask(CARD, USER, null)).willReturn(turn(StarField.R, "결과는 어땠나요?"), (InterviewTurnView) null);

        ToolResult asked = tools.execute(USER, 2, askCall());
        ToolResult nothing = tools.execute(USER, 3, askCall());

        assertThat(asked.ok()).isTrue();
        assertThat(asked.cardId()).isEqualTo(CARD);
        assertThat(asked.cardTitle()).isEqualTo("결제 서버 개선");
        assertThat(asked.question()).isEqualTo(new RunItem.Question("R", "결과는 어땠나요?"));
        assertThat(nothing.ok()).isTrue();
        assertThat(nothing.cardTitle()).isEqualTo("결제 서버 개선");
        assertThat(nothing.question()).isNull();

    }

    @Test
    @DisplayName("자소서 첨삭은 아직 첨삭하지 않은 문항을 번호 순서로 세 개까지 하고 남은 문항 수를 남긴다")
    void polishesUpToThreeUnpolishedQuestionsInOrder() {

        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        UUID fifth = UUID.randomUUID();
        List<CoverLetterView.Question> questions = List.of(question(UUID.randomUUID(), 1, "이미 첨삭한 글", 2),
                question(fifth, 5, "다섯째 글", -1), question(second, 2, "둘째 글", -1), question(third, 3, "셋째 글", -1),
                question(fourth, 4, "넷째 글", -1));
        given(letterReader.load(LETTER, USER)).willReturn(letter(CoverLetterStage.DRAFTED, questions));
        given(polishes.polish(LETTER, second, USER)).willReturn(polishedView(second, 2, 3));
        given(polishes.polish(LETTER, third, USER)).willReturn(polishedView(third, 3, 2));
        given(polishes.polish(LETTER, fourth, USER)).willReturn(polishedView(fourth, 4, 4));

        ToolResult result = tools.execute(USER, 2, polishCall());

        assertThat(result.ok()).isTrue();
        assertThat(result.letterId()).isEqualTo(LETTER);
        assertThat(result.polished()).containsExactly(new RunItem.Polished(2, 3), new RunItem.Polished(3, 2),
                new RunItem.Polished(4, 4));
        assertThat(result.remainingQuestions()).isEqualTo(1);
        var order = inOrder(polishes);
        order.verify(polishes).polish(LETTER, second, USER);
        order.verify(polishes).polish(LETTER, third, USER);
        order.verify(polishes).polish(LETTER, fourth, USER);
        verify(polishes, never()).polish(LETTER, fifth, USER);

    }

    @Test
    @DisplayName("초안이 끝나지 않은 자소서는 첨삭하지 않고 아직 준비되지 않음으로 남긴다")
    void refusesToPolishLetterThatIsNotDrafted() {

        given(letterReader.load(LETTER, USER)).willReturn(letter(CoverLetterStage.ASKING,
                List.of(question(UUID.randomUUID(), 1, "첫 글", -1))));

        ToolResult result = tools.execute(USER, 2, polishCall());

        assertThat(result.error()).isEqualTo(ToolError.NOT_READY);
        verifyNoInteractions(polishes);

    }

    @Test
    @DisplayName("첨삭 도중 호출 제한에 걸리면 거기까지 한 것을 남기고, 첫 문항부터 걸리면 호출 제한으로 남긴다")
    void keepsEarlierPolishesWhenRateLimitStopsTheRest() {

        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        given(letterReader.load(LETTER, USER)).willReturn(letter(CoverLetterStage.DRAFTED,
                List.of(question(second, 2, "둘째 글", -1), question(third, 3, "셋째 글", -1),
                        question(fourth, 4, "넷째 글", -1))));
        given(polishes.polish(LETTER, second, USER)).willReturn(polishedView(second, 2, 3));
        given(polishes.polish(LETTER, third, USER)).willThrow(new RateLimitExceededException(30));

        ToolResult partial = tools.execute(USER, 2, polishCall());

        assertThat(partial.ok()).isTrue();
        assertThat(partial.polished()).containsExactly(new RunItem.Polished(2, 3));
        assertThat(partial.remainingQuestions()).isEqualTo(2);
        verify(polishes, never()).polish(LETTER, fourth, USER);

        given(polishes.polish(LETTER, second, USER)).willThrow(new RateLimitExceededException(30));

        ToolResult none = tools.execute(USER, 3, polishCall());

        assertThat(none.error()).isEqualTo(ToolError.RATE_LIMITED);

    }

    @ParameterizedTest
    @MethodSource("failures")
    @DisplayName("도구가 던진 예외는 걸음 실패 코드로 바꾼다")
    void turnsToolExceptionsIntoStepErrors(AssistantTool tool, RuntimeException thrown, ToolError expected) {

        given(cardStats.confirmedCards(USER)).willReturn(1);
        given(matchList.list(null)).willThrow(thrown);
        given(matchDetails.detail(any(), any())).willThrow(thrown);
        given(coverLetters.create(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).willThrow(thrown);
        given(repoAnalysis.start(any(), any())).willThrow(thrown);
        given(gapFills.start(any(), any(), any())).willThrow(thrown);
        given(interviews.ask(any(), any(), any())).willThrow(thrown);
        given(letterReader.load(any(), any())).willThrow(thrown);

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
                Arguments.of(AssistantTool.START_REPO_ANALYSIS, new IllegalStateException("x"), ToolError.FAILED),
                Arguments.of(AssistantTool.FILL_POSTING_GAPS, new GapFillNeedsJudgementException(),
                        ToolError.NOT_MATCHED),
                Arguments.of(AssistantTool.FILL_POSTING_GAPS, new GithubNotConnectedException(),
                        ToolError.GITHUB_DISCONNECTED),
                Arguments.of(AssistantTool.FILL_POSTING_GAPS, new RateLimitExceededException(30),
                        ToolError.RATE_LIMITED),
                Arguments.of(AssistantTool.FILL_POSTING_GAPS, new MatchPostingNotFoundException(),
                        ToolError.NOT_FOUND),
                Arguments.of(AssistantTool.ASK_CARD_GAP, new InterviewCapReachedException(), ToolError.CAP_REACHED),
                Arguments.of(AssistantTool.ASK_CARD_GAP, new CardNotFoundException(), ToolError.NOT_FOUND),
                Arguments.of(AssistantTool.ASK_CARD_GAP, new RateLimitExceededException(30), ToolError.RATE_LIMITED),
                Arguments.of(AssistantTool.ASK_CARD_GAP, new IllegalStateException("x"), ToolError.FAILED),
                Arguments.of(AssistantTool.POLISH_COVER_LETTER, new CoverLetterNotFoundException(),
                        ToolError.NOT_FOUND));

    }

    private static ToolCall callOf(AssistantTool tool) {

        return switch (tool) {
            case FIND_POSTINGS -> findCall();
            case MATCH_POSTING -> matchCall(new RunItem.Posting("p1", POSTING, "토스", "백엔드", 2, null, null));
            case START_COVER_LETTER -> new ToolCall(RUN, tool, null, null, null, null, null, "백엔드 개발자",
                    List.of(new NewQuestion("지원 동기를 쓰세요", null)), null, null, null, null);
            case START_REPO_ANALYSIS -> new ToolCall(RUN, tool, null, null, null, "r1",
                    new RepoLabel("r1", REPO, "gitory"), null, List.of(), null, null, null, null);
            case FILL_POSTING_GAPS -> fillCall();
            case ASK_CARD_GAP -> askCall();
            case POLISH_COVER_LETTER -> polishCall();
        };

    }

    private static ToolCall findCall() {

        return new ToolCall(RUN, AssistantTool.FIND_POSTINGS, new RunItem.Find(null, List.of()), null, null, null,
                null, null, List.of(), null, null, null, null);

    }

    private static ToolCall matchCall(RunItem.Posting posting) {

        return new ToolCall(RUN, AssistantTool.MATCH_POSTING, null, "p1", posting, null, null, null, List.of(), null,
                null, null, null);

    }

    private static ToolCall fillCall() {

        return new ToolCall(RUN, AssistantTool.FILL_POSTING_GAPS, null, "p1",
                new RunItem.Posting("p1", POSTING, "토스", "백엔드", 2, null, null), null, null, null, List.of(), null,
                null, null, null);

    }

    private static ToolCall askCall() {

        return new ToolCall(RUN, AssistantTool.ASK_CARD_GAP, null, null, null, null, null, null, List.of(), "c1",
                new CardLabel("c1", CARD, "결제 서버 개선", List.of(StarField.R)), null, null);

    }

    private static ToolCall polishCall() {

        return new ToolCall(RUN, AssistantTool.POLISH_COVER_LETTER, null, null, null, null, null, null, List.of(),
                null, null, "l1", new LetterLabel("l1", LETTER, "백엔드 개발자", "토스", 5));

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

    private static GapFillView fillView(GapFillStatus state, Integer pollAfterMs) {

        return new GapFillView(state, pollAfterMs, List.of());

    }

    private static GapFillCondition gapCondition(GapFillResult result) {

        return new GapFillCondition(UUID.randomUUID(), null, null, false, List.of(), null, result);

    }

    private static InterviewTurnView turn(StarField field, String question) {

        return new InterviewTurnView(1, field, TriggerSource.USER, InterviewSourceType.DIRECT_CARD,
                QuestionType.EVIDENCE_GAP, question, null, AnswerMode.TEXT, List.of(), null, null, 5, 6);

    }

    // points 가 0 보다 작으면 아직 첨삭하지 않은 문항이다
    private static CoverLetterView.Question question(UUID id, int seq, String draftText, int points) {

        List<PolishPoint> polishPoints = IntStream.range(0, Math.max(points, 0))
                .mapToObj(index -> new PolishPoint(index + 1, null, null, "고칠 점", "설명", List.of()))
                .toList();
        QuestionPolish polish = points < 0 ? null : new QuestionPolish("요약", polishPoints, null, null);
        CoverLetterView.QuestionDraft draft = new CoverLetterView.QuestionDraft(draftText, draftText, false,
                draftText.length(), 0, List.of(), List.of(), List.of(), null);

        return new CoverLetterView.Question(id, seq, "문항 " + seq, 700, null, null, false, null, null, null,
                List.of(), List.of(), List.of(), draft, null, polish, 0, List.of());

    }

    private static CoverLetterView letter(CoverLetterStage stage, List<CoverLetterView.Question> questions) {

        return new CoverLetterView(LETTER, stage, null, null, null, false, "백엔드", null, List.of(), null, null, null,
                null, questions, List.of(), List.of(), false);

    }

    private static CoverLetterView polishedView(UUID questionId, int seq, int points) {

        return letter(CoverLetterStage.DRAFTED, List.of(question(questionId, seq, "글", points)));

    }

    private static CoverLetterView letterView(UUID id) {

        return new CoverLetterView(id, CoverLetterStage.ASSIGNING, null, null, null, true, "백엔드", null, List.of(),
                null, null, null, null, List.of(), List.of(), List.of(), false);

    }
}
