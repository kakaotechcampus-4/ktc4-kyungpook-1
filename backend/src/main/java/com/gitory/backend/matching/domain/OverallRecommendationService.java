package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.OverallRecommendationRepository;
import com.gitory.backend.profile.domain.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 모든 확정 카드로 맞는 기업을 5곳까지 계산해 저장하며, 공고 페이지는 이 저장본만 읽는다
 * 대표 공고의 필수·우대 조건을 모두 AI 에게 물어 '필수 N개 중 M개' 를 세고, 일반 조건(학력·경력 등)은 1단계 인적사항 거르기가 본다
 */
@Slf4j
@Service
public class OverallRecommendationService {

    static final int CANDIDATE_COMPANIES = 15;
    static final int SHOWN_COMPANIES = 5;
    // AI 판단 한 번이 10초 안팎이라 공고를 이만큼씩 함께 물어, 15곳을 30초 안팎에 끝낸다
    static final int PARALLEL_JUDGEMENTS = 5;
    // 계산 하나가 1분 안팎이라, 여러 사용자가 같이 눌러도 앞사람이 끝나기를 오래 기다리지 않게 둘씩 돌린다
    private static final int PARALLEL_USERS = 2;

    // 채운 필수 조건의 비율이 높은 회사가 먼저고, 같으면 채운 우대 조건 수, 그래도 같으면 1단계 순서다
    private static final Comparator<Scored> STRONGER_FIRST = Comparator
            .comparing(Scored::company, OverallRecommendationService::higherRequiredRatioFirst)
            .thenComparing(Comparator.comparingInt((Scored scored) -> scored.company().preferredMet()).reversed())
            .thenComparingInt(Scored::shortlistRank);

    private final MatchingCardReader cards;
    private final CompanyShortlistService shortlist;
    private final JobPostingCriterionRepository criteria;
    private final MatchJudgementService judgements;
    private final OverallRecommendationRepository recommendations;
    private final ProfileService profiles;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Executor runner;
    private final Executor judgeCalls;

    @Autowired
    public OverallRecommendationService(MatchingCardReader cards, CompanyShortlistService shortlist,
                                        JobPostingCriterionRepository criteria, MatchJudgementService judgements,
                                        OverallRecommendationRepository recommendations, ProfileService profiles,
                                        TransactionTemplate transaction, Clock clock) {
        this(cards, shortlist, criteria, judgements, recommendations, profiles, transaction, clock,
                Executors.newFixedThreadPool(PARALLEL_USERS,
                        Thread.ofPlatform().daemon().name("overall-recommend-", 1).factory()),
                Executors.newFixedThreadPool(PARALLEL_JUDGEMENTS,
                        Thread.ofPlatform().daemon().name("overall-judge-", 1).factory()));
    }

    OverallRecommendationService(MatchingCardReader cards, CompanyShortlistService shortlist,
                                 JobPostingCriterionRepository criteria, MatchJudgementService judgements,
                                 OverallRecommendationRepository recommendations, ProfileService profiles,
                                 TransactionTemplate transaction, Clock clock, Executor runner, Executor judgeCalls) {
        this.cards = cards;
        this.shortlist = shortlist;
        this.criteria = criteria;
        this.judgements = judgements;
        this.recommendations = recommendations;
        this.profiles = profiles;
        this.transaction = transaction;
        this.clock = clock;
        this.runner = runner;
        this.judgeCalls = judgeCalls;
    }

    /** 이미 계산 중이면 새로 시작하지 않고, 시작했으면 배경에서 계산한다 */
    public void start(Long userId) {

        Instant now = Instant.now(clock);
        if (recommendations.start(userId, now, now.minus(OverallRecommendation.STUCK_AFTER)) == 0) {
            return;
        }
        runner.execute(() -> computeOrFail(userId));

    }

    private void computeOrFail(Long userId) {

        try {
            compute(userId);
        } catch (RuntimeException failure) {
            log.warn("사용자 {} 의 전체 추천을 계산하지 못해 실패로 둔다", userId, failure);
            save(userId, true, List.of(), null);
        }

    }

    /** AI 를 부르는 동안 트랜잭션을 열지 않으며, 판단을 시도한 공고가 모두 실패했을 때만 실패로 저장한다 */
    void compute(Long userId) {

        List<MatchingCard> all = cards.confirmedOf(userId);
        String fingerprint = InputFingerprint.of(all, profiles.of(userId).savedAt());
        Set<CardTech> techs = all.stream()
                .flatMap(card -> card.techs().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ShortlistedCompany> candidates = techs.isEmpty() ? List.of()
                : shortlist.shortlist(userId, techs, CANDIDATE_COMPANIES);
        Map<Long, List<JobPostingCriterion>> byPosting = criteria.findByJobPostingIdInOrderByJobPostingIdAscSeqAsc(
                        candidates.stream().map(company -> company.representative().getId()).toList())
                .stream()
                .collect(Collectors.groupingBy(JobPostingCriterion::getJobPostingId));

        int total = candidates.size();
        recommendations.progress(userId, 0, total);
        AtomicInteger done = new AtomicInteger();
        List<CompletableFuture<Outcome>> futures = new ArrayList<>();
        for (int rank = 0; rank < total; rank++) {
            ShortlistedCompany company = candidates.get(rank);
            List<JobPostingCriterion> conditions = judged(
                    byPosting.getOrDefault(company.representative().getId(), List.of()));
            int shortlistRank = rank;
            futures.add(CompletableFuture.supplyAsync(() -> {
                Outcome outcome = outcome(company, conditions, all, shortlistRank);
                recommendations.progress(userId, done.incrementAndGet(), total);
                return outcome;
            }, judgeCalls));
        }
        List<Outcome> outcomes = futures.stream().map(CompletableFuture::join).toList();

        long asked = outcomes.stream().filter(Outcome::asked).count();
        long failed = outcomes.stream().filter(Outcome::failed).count();
        List<OverallCompany> shown = outcomes.stream()
                .map(Outcome::scored)
                .flatMap(Optional::stream)
                .sorted(STRONGER_FIRST)
                .limit(SHOWN_COMPANIES)
                .map(Scored::company)
                .toList();
        save(userId, asked > 0 && failed == asked, shown, fingerprint);

    }

    // 담당 업무와 일반 조건(학력·경력 등)은 빼고, 기술 이름이 없는 조건도 문장으로 판단하게 넣는다
    static List<JobPostingCriterion> judged(List<JobPostingCriterion> criteria) {

        return criteria.stream()
                .filter(c -> c.getType() == CriterionType.REQUIRED || c.getType() == CriterionType.PREFERRED)
                .filter(c -> c.getGeneralType() == null)
                .toList();

    }

    private Outcome outcome(ShortlistedCompany company, List<JobPostingCriterion> conditions, List<MatchingCard> all,
                            int shortlistRank) {

        if (conditions.isEmpty()) {
            return new Outcome(false, false, Optional.empty());
        }
        Optional<Map<Long, List<JudgedSupport>>> judged = judgements.judge(company.representative(), conditions, all);
        if (judged.isEmpty()) {
            return new Outcome(true, true, Optional.empty());
        }
        return new Outcome(true, false, scored(company, conditions, judged.get(), shortlistRank));

    }

    // 필수 조건을 하나도 채우지 못한 회사는 '필수 N개 중 0개' 라 추천하지 않는다
    private static Optional<Scored> scored(ShortlistedCompany company, List<JobPostingCriterion> conditions,
                                           Map<Long, List<JudgedSupport>> judged, int shortlistRank) {

        int requiredMet = met(conditions, CriterionType.REQUIRED, judged);
        if (requiredMet == 0) {
            return Optional.empty();
        }
        return Optional.of(new Scored(new OverallCompany(company.companyId(), company.companyName(),
                company.representative().getId(), requiredMet, total(conditions, CriterionType.REQUIRED),
                met(conditions, CriterionType.PREFERRED, judged), total(conditions, CriterionType.PREFERRED)),
                shortlistRank));

    }

    private static int total(List<JobPostingCriterion> conditions, CriterionType type) {

        return (int) conditions.stream().filter(c -> c.getType() == type).count();

    }

    private static int met(List<JobPostingCriterion> conditions, CriterionType type,
                           Map<Long, List<JudgedSupport>> judged) {

        return (int) conditions.stream()
                .filter(c -> c.getType() == type)
                .filter(c -> !judged.getOrDefault(c.getId(), List.of()).isEmpty())
                .count();

    }

    // 비율을 나눗셈 없이 견줘, 4개 중 3개(0.75)가 2개 중 1개(0.5)보다 앞선다
    private static int higherRequiredRatioFirst(OverallCompany left, OverallCompany right) {

        return Integer.compare(right.requiredMet() * left.requiredTotal(), left.requiredMet() * right.requiredTotal());

    }

    // 실패해도 앞서 계산한 기업과 그때의 지문은 남긴다
    private void save(Long userId, boolean failed, List<OverallCompany> shown, String fingerprint) {

        Instant now = Instant.now(clock);
        transaction.executeWithoutResult(status -> recommendations.findWithLockByUserId(userId).ifPresent(saved -> {
            if (failed) {
                saved.fail(now);
            } else {
                saved.refresh(shown, fingerprint, now);
            }
        }));

    }

    private record Outcome(boolean asked, boolean failed, Optional<Scored> scored) {
    }

    private record Scored(OverallCompany company, int shortlistRank) {
    }
}
