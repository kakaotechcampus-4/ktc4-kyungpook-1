package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.mail.domain.MailPosting;
import com.gitory.backend.mail.domain.MailService;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.PostingAlertRunRepository;
import com.gitory.backend.matching.infra.SyncLock;
import com.gitory.backend.notice.domain.NoticeKind;
import com.gitory.backend.notice.domain.NoticePayload;
import com.gitory.backend.notice.domain.NoticePosting;
import com.gitory.backend.notice.domain.NoticeService;
import com.gitory.backend.profile.domain.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewPostingAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T05:00:00Z");
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final MatchingCard CARD = new MatchingCard(11L, 7L, "결제 API", null, null,
            "Spring 으로 결제 API 를 만들었다", null, List.of(CardTech.JAVA, CardTech.SPRING));

    private final PostingAlertRunRepository runs = mock(PostingAlertRunRepository.class);
    private final JobPostingRepository postings = mock(JobPostingRepository.class);
    private final MatchingCardReader cards = mock(MatchingCardReader.class);
    private final CompanyShortlistService shortlist = mock(CompanyShortlistService.class);
    private final MatchJudgementService judgements = mock(MatchJudgementService.class);
    private final NoticeService notices = mock(NoticeService.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final MailService mails = mock(MailService.class);
    private final SyncLock lock = mock(SyncLock.class);
    private final Clock clock = mock(Clock.class);
    private final NewPostingAlertService service = new NewPostingAlertService(runs, postings, cards, shortlist,
            judgements, notices, profiles, mails, lock, clock, Runnable::run);

    private final Map<Long, List<ShortlistedPosting>> candidatesByUser = new HashMap<>();
    private final Map<Long, Optional<Map<Long, List<JudgedSupport>>>> verdicts = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {

        when(clock.instant()).thenReturn(NOW);
        when(lock.runExclusively(any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return true;
        });
        when(runs.findById(PostingAlertRun.ONLY_ID)).thenReturn(Optional.empty());
        when(postings.findIdsExtractedAfter(any(), any())).thenReturn(List.of(100L));
        when(cards.activeUserIds(any())).thenReturn(List.of(USER));
        when(cards.confirmedOf(any())).thenReturn(List.of(CARD));
        when(shortlist.postingsAmong(any(), any(), any()))
                .thenAnswer(call -> candidatesByUser.getOrDefault((Long) call.getArgument(0), List.of()));
        when(judgements.judge(any(), anyList(), anyList()))
                .thenAnswer(call -> verdicts.get(((JobPosting) call.getArgument(0)).getId()));

    }

    @Test
    @DisplayName("처음 돌 때는 지난 확인 시각이 없으므로 지금부터 24시간 전 뒤에 조건을 뽑은 공고만 본다")
    void firstRunLooksBackOnlyTwentyFourHours() {

        service.run();

        verify(postings).findIdsExtractedAfter(NOW.minus(Duration.ofHours(24)), NOW);

    }

    @Test
    @DisplayName("대상 사용자는 지금부터 14일 전 뒤에 카드를 만들거나 고친 확정 카드 사용자로 가린다")
    void asksForUsersWhoTouchedCardsInTheLastFourteenDays() {

        service.run();

        verify(cards).activeUserIds(NOW.minus(Duration.ofDays(14)));

    }

    @Test
    @DisplayName("사용자마다 겹친 순서대로 공고 5건까지만 AI 로 판정하고 나머지는 판정하지 않는다")
    void judgesAtMostFivePostingsPerUserInOverlapOrder() {

        List<ShortlistedPosting> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(candidate(2, 2, 0, 0));
        }
        candidatesByUser.put(USER, seven);

        service.run();

        for (int i = 0; i < 5; i++) {
            verify(judgements).judge(eq(seven.get(i).posting()), anyList(), eq(List.of(CARD)));
        }
        verify(judgements, never()).judge(eq(seven.get(5).posting()), anyList(), anyList());
        verify(judgements, never()).judge(eq(seven.get(6).posting()), anyList(), anyList());
        verify(shortlist).postingsAmong(USER, Set.of(CardTech.JAVA, CardTech.SPRING), Set.of(100L));

    }

    @Test
    @DisplayName("필수 조건의 절반 이상을 채운 공고만 필수 충족 비율, 우대 충족 수 순으로 3건까지 한 알림에 담는다")
    void alertsOnlyPostingsWithHalfOfRequiredMetBestFirstUpToThree() {

        ShortlistedPosting half = candidate(1, 2, 0, 0);
        ShortlistedPosting all = candidate(2, 2, 0, 0);
        ShortlistedPosting oneThird = candidate(1, 3, 0, 0);
        ShortlistedPosting threeQuarters = candidate(3, 4, 0, 0);
        ShortlistedPosting halfWithPreferred = candidate(2, 4, 1, 2);
        candidatesByUser.put(USER, List.of(half, all, oneThird, threeQuarters, halfWithPreferred));

        service.run();

        assertThat(alerted(USER)).extracting(NoticePosting::postingId).containsExactly(
                all.posting().getPublicId(), threeQuarters.posting().getPublicId(),
                halfWithPreferred.posting().getPublicId());

    }

    @Test
    @DisplayName("알림의 공고에는 공고 id·회사 이름·제목·채운 필수 조건 수·필수 조건 수·마감 시각이 담긴다")
    void alertedPostingCarriesTheFieldsTheScreenShows() {

        ShortlistedPosting found = candidate(3, 4, 0, 0, NOW.plusSeconds(86400));
        candidatesByUser.put(USER, List.of(found));

        service.run();

        assertThat(alerted(USER)).containsExactly(new NoticePosting(found.posting().getPublicId(), "토스",
                "백엔드 개발자", 3, 4, NOW.plusSeconds(86400)));

    }

    @Test
    @DisplayName("필수 조건의 절반을 못 채웠거나 필수 조건이 없는 공고뿐이면 알림을 남기지 않는다")
    void leavesNoNoticeWhenNoPostingHasHalfOfRequiredMet() {

        candidatesByUser.put(USER, List.of(candidate(1, 3, 0, 0), candidate(0, 2, 2, 2), candidate(0, 0, 1, 1)));

        service.run();

        verify(notices, never()).leave(any(), any(), any());

    }

    @Test
    @DisplayName("실행이 끝나면 확인한 시각을 이번 실행 시작 시각으로 옮기고 다음 실행은 그 뒤에 조건을 뽑은 공고만 본다")
    void movesTheCheckedTimeForwardSoTheNextRunSkipsCheckedPostings() {

        Instant tomorrow = NOW.plus(Duration.ofDays(1));
        when(clock.instant()).thenReturn(NOW, tomorrow);

        service.run();
        ArgumentCaptor<PostingAlertRun> saved = ArgumentCaptor.forClass(PostingAlertRun.class);
        verify(runs).save(saved.capture());
        assertThat(saved.getValue().getCheckedThrough()).isEqualTo(NOW);
        when(runs.findById(PostingAlertRun.ONLY_ID)).thenReturn(Optional.of(saved.getValue()));

        service.run();

        verify(postings).findIdsExtractedAfter(NOW, tomorrow);
        assertThat(saved.getValue().getCheckedThrough()).isEqualTo(tomorrow);

    }

    @Test
    @DisplayName("한 사용자의 확인이 예외로 실패해도 다른 사용자는 그대로 확인하고 확인한 시각도 옮긴다")
    void aFailingUserDoesNotStopTheOthers() {

        when(cards.activeUserIds(any())).thenReturn(List.of(USER, OTHER_USER));
        when(cards.confirmedOf(USER)).thenThrow(new IllegalStateException("카드를 읽지 못함"));
        ShortlistedPosting found = candidate(2, 2, 0, 0);
        candidatesByUser.put(OTHER_USER, List.of(found));

        service.run();

        assertThat(alerted(OTHER_USER)).extracting(NoticePosting::postingId).containsExactly(found.posting().getPublicId());
        verify(runs).save(any());

    }

    @Test
    @DisplayName("AI 판정을 받지 못한 공고는 빼고 같은 사용자의 나머지 공고로 계속 확인한다")
    void anUnjudgeablePostingIsSkippedAndTheRestContinue() {

        ShortlistedPosting unjudgeable = candidate(2, 2, 0, 0);
        verdicts.put(unjudgeable.posting().getId(), Optional.empty());
        ShortlistedPosting judged = candidate(2, 2, 0, 0);
        candidatesByUser.put(USER, List.of(unjudgeable, judged));

        service.run();

        assertThat(alerted(USER)).extracting(NoticePosting::postingId).containsExactly(judged.posting().getPublicId());

    }

    @Test
    @DisplayName("실행 전체가 예외로 끝나도 밖으로 던지지 않고 확인한 시각은 옮기지 않아 다음 실행이 같은 구간을 다시 본다")
    void aFailingRunThrowsNothingAndKeepsTheCheckedTime() {

        when(postings.findIdsExtractedAfter(any(), any())).thenThrow(new IllegalStateException("DB 오류"));

        assertThatCode(service::run).doesNotThrowAnyException();

        verify(runs, never()).save(any());

    }

    @Test
    @DisplayName("공고 가져오기가 다른 곳에서 실행 중이라 잠금을 못 잡으면 확인하지 않는다")
    void doesNothingWhenTheLockIsHeldElsewhere() {

        doReturn(false).when(lock).runExclusively(any());

        service.run();

        verify(postings, never()).findIdsExtractedAfter(any(), any());
        verify(runs, never()).save(any());

    }

    @Test
    @DisplayName("스위치 gitory.matching.alert.enabled 가 꺼져 있거나 없으면 알림 확인 빈을 만들지 않고 켜면 만든다")
    void createsTheServiceOnlyWhenTheSwitchIsOn() {

        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(NewPostingAlertService.class)
                .withBean(PostingAlertRunRepository.class, () -> runs)
                .withBean(JobPostingRepository.class, () -> postings)
                .withBean(MatchingCardReader.class, () -> cards)
                .withBean(CompanyShortlistService.class, () -> shortlist)
                .withBean(MatchJudgementService.class, () -> judgements)
                .withBean(NoticeService.class, () -> notices)
                .withBean(ProfileService.class, () -> profiles)
                .withBean(MailService.class, () -> mails)
                .withBean(SyncLock.class, () -> lock)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));

        runner.run(context -> assertThat(context).doesNotHaveBean(NewPostingAlertService.class));
        runner.withPropertyValues("gitory.matching.alert.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NewPostingAlertService.class));
        runner.withPropertyValues("gitory.matching.alert.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(NewPostingAlertService.class));

    }

    @Test
    @DisplayName("알림을 남긴 사용자가 메일 수신을 켰으면 알림과 같은 공고 목록을 그 주소로 메일로 부탁한다")
    void mailsTheSamePostingsToAnOptedInUser() {

        ShortlistedPosting found = candidate(3, 4, 0, 0, NOW.plusSeconds(86400));
        candidatesByUser.put(USER, List.of(found));
        when(profiles.mailOptInUserIds()).thenReturn(List.of(USER));
        when(profiles.mailAddressOf(USER)).thenReturn(Optional.of("me@example.com"));

        service.run();

        verify(mails).sendMatchedPostings("me@example.com", List.of(new MailPosting(found.posting().getPublicId(),
                "토스", "백엔드 개발자", 3, 4, NOW.plusSeconds(86400))));

    }

    @Test
    @DisplayName("알릴 공고가 없으면 수신을 켠 사용자에게도 메일을 부탁하지 않는다")
    void sendsNoMailWhenThereIsNothingToAlert() {

        candidatesByUser.put(USER, List.of(candidate(1, 3, 0, 0)));
        when(profiles.mailOptInUserIds()).thenReturn(List.of(USER));
        when(profiles.mailAddressOf(USER)).thenReturn(Optional.of("me@example.com"));

        service.run();

        verify(mails, never()).sendMatchedPostings(any(), any());

    }

    @Test
    @DisplayName("메일 수신을 켜지 않은 사용자에게는 알림만 남기고 메일은 부탁하지 않는다")
    void leavesOnlyANoticeForAUserWhoDidNotOptIn() {

        candidatesByUser.put(USER, List.of(candidate(2, 2, 0, 0)));
        when(profiles.mailAddressOf(USER)).thenReturn(Optional.of("me@example.com"));

        service.run();

        verify(notices).leave(eq(USER), eq(NoticeKind.POSTINGS_MATCHED), any());
        verify(mails, never()).sendMatchedPostings(any(), any());

    }

    @Test
    @DisplayName("확인 대상은 최근 14일 안에 쓴 사용자와 수신을 켠 사용자를 합친 것이고 겹치는 사용자는 한 번만 id 순으로 본다")
    void checksActiveAndOptedInUsersOnceEachInIdOrder() {

        when(cards.activeUserIds(any())).thenReturn(List.of(5L, 1L));
        when(profiles.mailOptInUserIds()).thenReturn(List.of(3L, 1L));

        service.run();

        InOrder order = inOrder(cards);
        order.verify(cards).confirmedOf(1L);
        order.verify(cards).confirmedOf(3L);
        order.verify(cards).confirmedOf(5L);
        verify(cards, times(3)).confirmedOf(any());

    }

    private List<NoticePosting> alerted(Long userId) {

        ArgumentCaptor<NoticePayload> payload = ArgumentCaptor.forClass(NoticePayload.class);
        verify(notices, times(1)).leave(eq(userId), eq(NoticeKind.POSTINGS_MATCHED), payload.capture());
        return payload.getValue().postings();

    }

    private ShortlistedPosting candidate(int requiredMet, int requiredTotal, int preferredMet, int preferredTotal) {

        return candidate(requiredMet, requiredTotal, preferredMet, preferredTotal, null);

    }

    // 필수 조건 requiredTotal 개·우대 조건 preferredTotal 개를 만들고 앞에서부터 met 개를 채웠다고 판단해 둔다
    private ShortlistedPosting candidate(int requiredMet, int requiredTotal, int preferredMet, int preferredTotal,
                                         Instant expiresAt) {

        JobPosting posting = JobPosting.discovered("toss", "p" + nextId, "토스", "백엔드 개발자",
                "https://jobs.example.com/" + nextId, null, expiresAt, NOW);
        ReflectionTestUtils.setField(posting, "id", nextId++);
        List<JobPostingCriterion> criteria = new ArrayList<>();
        Map<Long, List<JudgedSupport>> judged = new HashMap<>();
        for (int i = 0; i < requiredTotal; i++) {
            JobPostingCriterion required = criterion(posting, CriterionType.REQUIRED, criteria.size() + 1);
            criteria.add(required);
            judged.put(required.getId(), i < requiredMet ? met() : List.of());
        }
        for (int i = 0; i < preferredTotal; i++) {
            JobPostingCriterion preferred = criterion(posting, CriterionType.PREFERRED, criteria.size() + 1);
            criteria.add(preferred);
            judged.put(preferred.getId(), i < preferredMet ? met() : List.of());
        }
        verdicts.put(posting.getId(), Optional.of(judged));
        return new ShortlistedPosting(posting, criteria,
                new TechOverlap(List.of("SPRING"), 1, List.of(), List.of()));
    }

    private JobPostingCriterion criterion(JobPosting posting, CriterionType type, int seq) {

        JobPostingCriterion criterion = JobPostingCriterion.extracted(posting.getId(), type, seq, "조건", "조건", null,
                List.of(CardTech.SPRING), null, false);
        ReflectionTestUtils.setField(criterion, "id", nextId++);
        return criterion;

    }

    private static List<JudgedSupport> met() {

        return List.of(new JudgedSupport(11L, "이유"));

    }
}
