package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.OverallRecommendationRepository;
import com.gitory.backend.profile.domain.ProfileService;
import com.gitory.backend.profile.domain.ProfileView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverallRecommendationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final Instant PROFILE_SAVED = Instant.parse("2026-10-30T00:00:00Z");
    private static final Long USER = 1L;
    private static final MatchingCard CARD = new MatchingCard(11L, 7L, "결제 API", null, null,
            "Spring 으로 결제 API 를 만들었다", null, List.of(CardTech.JAVA, CardTech.SPRING));

    private final MatchingCardReader cards = mock(MatchingCardReader.class);
    private final CompanyShortlistService shortlist = mock(CompanyShortlistService.class);
    private final JobPostingCriterionRepository criteria = mock(JobPostingCriterionRepository.class);
    private final MatchJudgementService judgements = mock(MatchJudgementService.class);
    private final OverallRecommendationRepository recommendations = mock(OverallRecommendationRepository.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final OverallRecommendation saved = mock(OverallRecommendation.class);
    private final List<Runnable> started = new ArrayList<>();
    private final AtomicInteger judgeTasks = new AtomicInteger();
    private final Executor judgeCalls = task -> {
        judgeTasks.incrementAndGet();
        task.run();
    };
    private final OverallRecommendationService service = new OverallRecommendationService(cards, shortlist, criteria,
            judgements, recommendations, profiles, new TransactionTemplate(mock(PlatformTransactionManager.class)),
            Clock.fixed(NOW, ZoneOffset.UTC), started::add, judgeCalls);

    private final List<ShortlistedCompany> companies = new ArrayList<>();
    private final List<JobPostingCriterion> allCriteria = new ArrayList<>();
    private final Map<Long, Optional<Map<Long, List<JudgedSupport>>>> verdicts = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {

        when(cards.confirmedOf(USER)).thenReturn(List.of(CARD));
        when(profiles.of(USER)).thenReturn(new ProfileView(List.of(), List.of(), List.of(), List.of(), PROFILE_SAVED));
        when(shortlist.shortlist(eq(USER), any(), any(), anyInt())).thenReturn(companies);
        when(criteria.findByJobPostingIdInOrderByJobPostingIdAscSeqAsc(any())).thenReturn(allCriteria);
        when(judgements.judge(any(), anyList(), anyList()))
                .thenAnswer(call -> verdicts.get(((JobPosting) call.getArgument(0)).getId()));
        when(recommendations.findWithLockByUserId(USER)).thenReturn(Optional.of(saved));

    }

    @Test
    @DisplayName("모든 확정 카드의 기술로 1단계 상위 15곳을 고르고, 대표 공고의 필수·우대 조건은 기술 이름이 없거나 카드와 겹치지 않아도 모두 묻되 담당 업무·일반 조건은 묻지 않는다")
    void asksEveryRequiredAndPreferredConditionOfTheTopFifteen() {

        JobPosting posting = company("toss");
        JobPostingCriterion spring = criterion(posting, CriterionType.REQUIRED, null, CardTech.SPRING);
        JobPostingCriterion queue = criterion(posting, CriterionType.PREFERRED, null);
        JobPostingCriterion kafka = criterion(posting, CriterionType.REQUIRED, null, CardTech.KAFKA);
        criterion(posting, CriterionType.RESPONSIBILITY, null, CardTech.SPRING);
        criterion(posting, CriterionType.REQUIRED, GeneralConditionType.OTHER, CardTech.JAVA);
        judged(posting, Map.of(spring.getId(), met()));

        service.compute(USER);

        verify(shortlist).shortlist(USER, Set.of(CardTech.JAVA, CardTech.SPRING), Set.of(), 15);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobPostingCriterion>> asked = ArgumentCaptor.forClass(List.class);
        verify(judgements).judge(eq(posting), asked.capture(), eq(List.of(CARD)));
        assertThat(asked.getValue()).containsExactly(spring, queue, kafka);

    }

    @Test
    @DisplayName("채운 필수 조건의 비율, 채운 우대 조건 수, 1단계 순서로 줄 세워 5곳만 저장한다")
    void ranksByRequiredRatioThenPreferredThenShortlistOrderAndKeepsFive() {

        withMet("a", 1, 2, 0, 0);
        withMet("b", 3, 4, 0, 0);
        withMet("c", 1, 2, 1, 1);
        withMet("d", 2, 2, 0, 0);
        withMet("e", 1, 1, 0, 0);
        withMet("f", 1, 3, 0, 0);

        service.compute(USER);

        assertThat(refreshed()).extracting(OverallCompany::companyId).containsExactly("d", "e", "b", "c", "a");
        assertThat(refreshed().get(2)).isEqualTo(new OverallCompany("b", "b", companies.get(1).representative().getId(),
                3, 4, 0, 0));

    }

    @Test
    @DisplayName("우대 조건만 채우고 필수 조건을 하나도 채우지 못한 회사는 넣지 않는다")
    void leavesOutCompaniesWithoutAnyRequiredMet() {

        withMet("a", 0, 2, 2, 2);
        withMet("b", 1, 1, 0, 0);

        service.compute(USER);

        assertThat(refreshed()).extracting(OverallCompany::companyId).containsExactly("b");

    }

    @Test
    @DisplayName("AI 판단을 받지 못한 공고는 그 회사만 빼고 계산해 저장한다")
    void leavesOutOnlyThePostingWhoseJudgementFailed() {

        withMet("a", 1, 1, 0, 0);
        JobPosting failing = company("b");
        criterion(failing, CriterionType.REQUIRED, null, CardTech.SPRING);
        verdicts.put(failing.getId(), Optional.empty());

        service.compute(USER);

        assertThat(refreshed()).extracting(OverallCompany::companyId).containsExactly("a");
        verify(saved, never()).fail(any());

    }

    @Test
    @DisplayName("물어본 공고가 모두 실패하면 앞서 저장한 기업은 두고 실패로만 바꾼다")
    void failsWithoutTouchingCompaniesWhenEveryAskedPostingFails() {

        JobPosting failing = company("a");
        criterion(failing, CriterionType.REQUIRED, null, CardTech.SPRING);
        verdicts.put(failing.getId(), Optional.empty());

        service.compute(USER);

        verify(saved).fail(NOW);
        verify(saved, never()).refresh(anyList(), any(), any());

    }

    @Test
    @DisplayName("계산에 쓴 확정 카드 글과 인적사항 저장 시각의 지문을 결과와 함께 저장한다")
    void savesFingerprintOfConfirmedCardsAndProfile() {

        withMet("a", 1, 1, 0, 0);

        service.compute(USER);

        verify(saved).refresh(anyList(), eq(InputFingerprint.of(List.of(CARD), PROFILE_SAVED)), eq(NOW));

    }

    @Test
    @DisplayName("공고 수를 먼저 남기고 공고 하나를 판단할 때마다 확인한 수를 하나씩 늘리며, 판단은 공고마다 함께 묻는 실행기에서 한다")
    void reportsProgressForEachJudgedPostingOnTheJudgeExecutor() {

        withMet("a", 1, 1, 0, 0);
        withMet("b", 1, 1, 0, 0);
        withMet("c", 1, 1, 0, 0);

        service.compute(USER);

        InOrder order = inOrder(recommendations);
        order.verify(recommendations).progress(USER, 0, 3);
        order.verify(recommendations).progress(USER, 1, 3);
        order.verify(recommendations).progress(USER, 2, 3);
        order.verify(recommendations).progress(USER, 3, 3);
        assertThat(judgeTasks).hasValue(3);

    }

    @Test
    @DisplayName("이미 계산 중이면 새로 시작하지 않고, 시작했을 때만 배경에서 계산한다")
    void startsInTheBackgroundOnlyWhenNotAlreadyComputing() {

        when(recommendations.start(USER, NOW, NOW.minus(OverallRecommendation.STUCK_AFTER))).thenReturn(0);
        service.start(USER);
        assertThat(started).isEmpty();

        when(recommendations.start(USER, NOW, NOW.minus(OverallRecommendation.STUCK_AFTER))).thenReturn(1);
        service.start(USER);
        assertThat(started).hasSize(1);
        verify(cards, never()).confirmedOf(USER);

        started.getFirst().run();
        verify(cards).confirmedOf(USER);

    }

    @Test
    @DisplayName("배경 계산이 예상하지 못한 이유로 멈추면 실패로 저장해 화면이 계속 기다리지 않게 한다")
    void savesFailureWhenBackgroundComputeBreaks() {

        when(recommendations.start(USER, NOW, NOW.minus(OverallRecommendation.STUCK_AFTER))).thenReturn(1);
        when(cards.confirmedOf(USER)).thenThrow(new IllegalStateException("카드를 읽지 못함"));

        service.start(USER);
        started.getFirst().run();

        verify(saved).fail(NOW);

    }

    @Test
    @DisplayName("확정 카드가 없으면 1단계도 AI 도 부르지 않고 빈 추천을 저장한다")
    void savesEmptyRecommendationWithoutAskingWhenThereAreNoConfirmedCards() {

        when(cards.confirmedOf(USER)).thenReturn(List.of());

        service.compute(USER);

        verify(shortlist, never()).shortlist(any(), any(), any(), anyInt());
        verify(judgements, never()).judge(any(), anyList(), anyList());
        assertThat(refreshed()).isEmpty();

    }

    // 회사 하나에 필수 조건 requiredTotal 개·우대 조건 preferredTotal 개를 만들고 앞에서부터 met 개를 채웠다고 판단해 둔다
    private void withMet(String companyId, int requiredMet, int requiredTotal, int preferredMet, int preferredTotal) {

        JobPosting posting = company(companyId);
        Map<Long, List<JudgedSupport>> judged = new HashMap<>();
        for (int i = 0; i < requiredTotal; i++) {
            judged.put(criterion(posting, CriterionType.REQUIRED, null, CardTech.SPRING).getId(),
                    i < requiredMet ? met() : List.of());
        }
        for (int i = 0; i < preferredTotal; i++) {
            judged.put(criterion(posting, CriterionType.PREFERRED, null).getId(), i < preferredMet ? met() : List.of());
        }
        judged(posting, judged);

    }

    private JobPosting company(String companyId) {

        JobPosting posting = JobPosting.discovered(companyId, "1", companyId, "백엔드 개발자",
                "https://jobs.example.com/" + companyId, null, null, NOW);
        ReflectionTestUtils.setField(posting, "id", nextId++);
        companies.add(new ShortlistedCompany(companyId, companyId, posting,
                new TechOverlap(List.of("SPRING"), 1, List.of(), List.of()), 1));
        return posting;

    }

    private JobPostingCriterion criterion(JobPosting posting, CriterionType type, GeneralConditionType general,
                                          CardTech... techs) {

        JobPostingCriterion criterion = JobPostingCriterion.extracted(posting.getId(), type, allCriteria.size() + 1,
                "조건", "조건", general, List.of(techs), null, false);
        ReflectionTestUtils.setField(criterion, "id", nextId++);
        allCriteria.add(criterion);
        return criterion;

    }

    private void judged(JobPosting posting, Map<Long, List<JudgedSupport>> judged) {

        verdicts.put(posting.getId(), Optional.of(judged));

    }

    private static List<JudgedSupport> met() {

        return List.of(new JudgedSupport(11L, "이유"));

    }

    private List<OverallCompany> refreshed() {

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OverallCompany>> shown = ArgumentCaptor.forClass(List.class);
        verify(saved).refresh(shown.capture(), any(), eq(NOW));
        return shown.getValue();

    }
}
