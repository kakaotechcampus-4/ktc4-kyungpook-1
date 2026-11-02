package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.RepoRecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoRecommendationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private static final Long USER = 1L;
    private static final Long REPO = 7L;

    private final MatchingCardReader cards = mock(MatchingCardReader.class);
    private final CompanyShortlistService shortlist = mock(CompanyShortlistService.class);
    private final JobPostingCriterionRepository criteria = mock(JobPostingCriterionRepository.class);
    private final MatchJudgementService judgements = mock(MatchJudgementService.class);
    private final RepoRecommendationRepository recommendations = mock(RepoRecommendationRepository.class);
    private final RepoRecommendationService service = new RepoRecommendationService(cards, shortlist, criteria,
            judgements, recommendations, new TransactionTemplate(mock(PlatformTransactionManager.class)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<ShortlistedCompany> companies = new ArrayList<>();
    private final List<JobPostingCriterion> allCriteria = new ArrayList<>();
    private final Map<Long, Optional<Map<Long, List<JudgedSupport>>>> verdicts = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {

        when(cards.confirmedOf(USER, REPO)).thenReturn(List.of(new MatchingCard(11L, REPO, "결제 API", null, null,
                "Spring 으로 결제 API 를 만들었다", null,
                List.of(CardTech.JAVA, CardTech.SPRING, CardTech.JPA, CardTech.REDIS))));
        when(shortlist.shortlist(eq(USER), any(), anyInt())).thenReturn(companies);
        when(criteria.findByJobPostingIdInOrderByJobPostingIdAscSeqAsc(any())).thenReturn(allCriteria);
        when(judgements.judge(any(), anyList(), anyList()))
                .thenAnswer(call -> verdicts.get(((JobPosting) call.getArgument(0)).getId()));
        when(recommendations.findWithLockByUserRepositoryId(REPO)).thenReturn(Optional.empty());

    }

    @Test
    @DisplayName("레포 카드 기술과 겹친 필수·우대 기술 조건만 AI 에게 묻고 담당 업무·일반 조건·안 겹친 조건은 묻지 않는다")
    void asksOnlyTechOverlappingRequiredAndPreferredConditions() {

        JobPosting posting = company("toss");
        JobPostingCriterion spring = criterion(posting, CriterionType.REQUIRED, null, CardTech.SPRING);
        JobPostingCriterion redis = criterion(posting, CriterionType.PREFERRED, null, CardTech.REDIS);
        criterion(posting, CriterionType.REQUIRED, null, CardTech.KAFKA);
        criterion(posting, CriterionType.RESPONSIBILITY, null, CardTech.SPRING);
        criterion(posting, CriterionType.REQUIRED, GeneralConditionType.OTHER, CardTech.JAVA);
        judged(posting, Map.of(spring.getId(), met()));

        service.compute(USER, REPO);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobPostingCriterion>> asked = ArgumentCaptor.forClass(List.class);
        verify(judgements).judge(eq(posting), asked.capture(), anyList());
        assertThat(asked.getValue()).containsExactly(spring, redis);

    }

    @Test
    @DisplayName("채운 필수 조건 수, 채운 우대 조건 수, 1단계 순서로 줄 세워 3곳만 저장한다")
    void ranksByRequiredThenPreferredThenShortlistOrderAndKeepsThree() {

        withMet("a", 1, 0);
        withMet("b", 2, 0);
        withMet("c", 1, 1);
        withMet("d", 1, 0);

        service.compute(USER, REPO);

        assertThat(saved().getCompanies()).extracting(RecommendedCompany::companyId).containsExactly("b", "c", "a");

    }

    @Test
    @DisplayName("AI 가 채움이라고 한 조건이 하나도 없는 회사는 넣지 않는다")
    void leavesOutCompaniesWithoutAnyMetCondition() {

        withMet("a", 0, 0);
        withMet("b", 1, 0);

        service.compute(USER, REPO);

        assertThat(saved().getCompanies()).extracting(RecommendedCompany::companyId).containsExactly("b");

    }

    @Test
    @DisplayName("AI 판단을 받지 못한 공고는 그 회사만 빼고 계산해 준비됨으로 저장한다")
    void leavesOutOnlyThePostingWhoseJudgementFailed() {

        withMet("a", 1, 0);
        JobPosting failing = company("b");
        criterion(failing, CriterionType.REQUIRED, null, CardTech.SPRING);
        verdicts.put(failing.getId(), Optional.empty());

        service.compute(USER, REPO);

        RepoRecommendation saved = saved();
        assertThat(saved.getStatus()).isEqualTo(RepoRecommendationStatus.READY);
        assertThat(saved.getCompanies()).extracting(RecommendedCompany::companyId).containsExactly("a");

    }

    @Test
    @DisplayName("물어본 공고가 모두 실패하면 앞서 저장한 기업은 그대로 두고 상태만 실패로 바꾼다")
    void keepsPreviousCompaniesAndFailsWhenEveryAskedPostingFails() {

        RecommendedCompany previous = new RecommendedCompany("old", "옛 회사", 99L, 1, 0, List.of("JAVA"), List.of(1L));
        RepoRecommendation existing = RepoRecommendation.ready(USER, REPO, List.of(previous), NOW.minusSeconds(3600));
        when(recommendations.findWithLockByUserRepositoryId(REPO)).thenReturn(Optional.of(existing));
        JobPosting failing = company("a");
        criterion(failing, CriterionType.REQUIRED, null, CardTech.SPRING);
        verdicts.put(failing.getId(), Optional.empty());

        service.compute(USER, REPO);

        assertThat(existing.getStatus()).isEqualTo(RepoRecommendationStatus.FAILED);
        assertThat(existing.getCompanies()).containsExactly(previous);
        assertThat(existing.getComputedAt()).isEqualTo(NOW);

    }

    @Test
    @DisplayName("한 줄 이유의 기술 이름은 채운 조건의 기술 중 이 레포 카드에 있는 것만 3개까지 담는다")
    void keepsUpToThreeMetTechsThatTheRepoCardsHave() {

        JobPosting posting = company("a");
        JobPostingCriterion first = criterion(posting, CriterionType.REQUIRED, null, CardTech.SPRING, CardTech.KAFKA);
        JobPostingCriterion second = criterion(posting, CriterionType.REQUIRED, null, CardTech.JPA, CardTech.REDIS,
                CardTech.JAVA);
        judged(posting, Map.of(first.getId(), met(), second.getId(), met()));

        service.compute(USER, REPO);

        RecommendedCompany company = saved().getCompanies().getFirst();
        assertThat(company.techs()).containsExactly("SPRING", "JPA", "REDIS");
        assertThat(company.metCriterionIds()).containsExactly(first.getId(), second.getId());

    }

    @Test
    @DisplayName("레포에 확정 카드가 없으면 AI 를 부르지 않고 빈 추천을 준비됨으로 저장한다")
    void savesEmptyRecommendationWithoutAskingWhenRepoHasNoConfirmedCards() {

        when(cards.confirmedOf(USER, REPO)).thenReturn(List.of());

        service.compute(USER, REPO);

        verify(judgements, never()).judge(any(), anyList(), anyList());
        assertThat(saved().getStatus()).isEqualTo(RepoRecommendationStatus.READY);
        assertThat(saved().getCompanies()).isEmpty();

    }

    // 회사 하나에 필수·우대 기술 조건을 required·preferred 개 만들고 모두 채웠다고 판단해 둔다
    private void withMet(String companyId, int required, int preferred) {

        JobPosting posting = company(companyId);
        Map<Long, List<JudgedSupport>> judged = new HashMap<>();
        for (int i = 0; i < required; i++) {
            judged.put(criterion(posting, CriterionType.REQUIRED, null, CardTech.SPRING).getId(), met());
        }
        for (int i = 0; i < preferred; i++) {
            judged.put(criterion(posting, CriterionType.PREFERRED, null, CardTech.REDIS).getId(), met());
        }
        if (required + preferred == 0) {
            judged.put(criterion(posting, CriterionType.REQUIRED, null, CardTech.SPRING).getId(), List.of());
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

    private RepoRecommendation saved() {

        ArgumentCaptor<RepoRecommendation> saved = ArgumentCaptor.forClass(RepoRecommendation.class);
        verify(recommendations).save(saved.capture());
        return saved.getValue();

    }
}
