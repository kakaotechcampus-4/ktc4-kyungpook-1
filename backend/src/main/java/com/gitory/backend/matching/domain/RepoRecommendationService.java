package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardReader;
import com.gitory.backend.matching.infra.JobPostingCriterionRepository;
import com.gitory.backend.matching.infra.RepoRecommendationRepository;
import lombok.RequiredArgsConstructor;
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
import java.util.stream.Collectors;

/**
 * 레포 하나의 확정 카드로 추천 기업 3곳을 계산해 저장하며, 레포 화면은 이 저장본만 읽는다
 * AI 에게는 그 레포 카드와 기술이 겹친 조건만 물어, 레포가 공고 전체가 아니라 채운 조건만 말하게 한다
 */
@Service
@RequiredArgsConstructor
public class RepoRecommendationService {

    static final int CANDIDATE_COMPANIES = 10;
    static final int SHOWN_COMPANIES = 3;
    static final int SHOWN_TECHS = 3;

    // 채운 필수 조건이 많은 회사가 먼저고, 같으면 채운 우대 조건, 그래도 같으면 1단계 순서다
    private static final Comparator<Scored> STRONGER_FIRST = Comparator
            .comparingInt((Scored scored) -> scored.company().requiredMet()).reversed()
            .thenComparing(Comparator.comparingInt((Scored scored) -> scored.company().preferredMet()).reversed())
            .thenComparingInt(Scored::shortlistRank);

    private final MatchingCardReader cards;
    private final CompanyShortlistService shortlist;
    private final JobPostingCriterionRepository criteria;
    private final MatchJudgementService judgements;
    private final RepoRecommendationRepository recommendations;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** AI 를 부르는 동안 트랜잭션을 열지 않으며, 판단을 시도한 공고가 모두 실패했을 때만 실패로 저장한다 */
    public void compute(Long userId, Long userRepositoryId) {

        List<MatchingCard> repoCards = cards.confirmedOf(userId, userRepositoryId);
        Set<CardTech> techs = repoCards.stream()
                .flatMap(card -> card.techs().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ShortlistedCompany> candidates = techs.isEmpty() ? List.of()
                : shortlist.shortlist(userId, techs, CANDIDATE_COMPANIES);
        Map<Long, List<JobPostingCriterion>> byPosting = criteria.findByJobPostingIdInOrderByJobPostingIdAscSeqAsc(
                        candidates.stream().map(company -> company.representative().getId()).toList())
                .stream()
                .collect(Collectors.groupingBy(JobPostingCriterion::getJobPostingId));
        Set<String> techNames = techs.stream().map(CardTech::name).collect(Collectors.toSet());

        List<Scored> scored = new ArrayList<>();
        int asked = 0;
        int failed = 0;
        for (int rank = 0; rank < candidates.size(); rank++) {
            ShortlistedCompany company = candidates.get(rank);
            List<JobPostingCriterion> overlapping = overlapping(
                    byPosting.getOrDefault(company.representative().getId(), List.of()), techNames);
            if (overlapping.isEmpty()) {
                continue;
            }
            asked++;
            Optional<Map<Long, List<JudgedSupport>>> judged =
                    judgements.judge(company.representative(), overlapping, repoCards);
            if (judged.isEmpty()) {
                failed++;
                continue;
            }
            int shortlistRank = rank;
            recommended(company, overlapping, judged.get(), techNames)
                    .ifPresent(recommended -> scored.add(new Scored(recommended, shortlistRank)));
        }

        boolean allFailed = asked > 0 && failed == asked;
        List<RecommendedCompany> shown = scored.stream()
                .sorted(STRONGER_FIRST)
                .limit(SHOWN_COMPANIES)
                .map(Scored::company)
                .toList();
        save(userId, userRepositoryId, allFailed, shown);

    }

    // 레포 카드 기술과 이름이 겹친 필수·우대 기술 조건만 묻고, 일반 조건은 인적사항이 따로 본다
    static List<JobPostingCriterion> overlapping(List<JobPostingCriterion> criteria, Set<String> techNames) {

        return criteria.stream()
                .filter(c -> c.getType() == CriterionType.REQUIRED || c.getType() == CriterionType.PREFERRED)
                .filter(c -> c.getGeneralType() == null)
                .filter(c -> c.getTechs().stream().anyMatch(techNames::contains))
                .toList();

    }

    // 채운 조건이 하나도 없는 회사는 이유를 말할 수 없어 넣지 않는다
    private static Optional<RecommendedCompany> recommended(ShortlistedCompany company,
                                                            List<JobPostingCriterion> overlapping,
                                                            Map<Long, List<JudgedSupport>> judged,
                                                            Set<String> techNames) {

        List<JobPostingCriterion> met = overlapping.stream()
                .filter(c -> !judged.getOrDefault(c.getId(), List.of()).isEmpty())
                .toList();
        if (met.isEmpty()) {
            return Optional.empty();
        }
        int requiredMet = (int) met.stream().filter(c -> c.getType() == CriterionType.REQUIRED).count();
        List<String> techs = met.stream()
                .flatMap(c -> c.getTechs().stream())
                .filter(techNames::contains)
                .distinct()
                .limit(SHOWN_TECHS)
                .toList();
        return Optional.of(new RecommendedCompany(company.companyId(), company.companyName(),
                company.representative().getId(), requiredMet, met.size() - requiredMet, techs,
                met.stream().map(JobPostingCriterion::getId).toList()));

    }

    /** 계산이 예상하지 못한 이유로 멈췄을 때 부르며, 앞서 계산한 기업은 남긴다 */
    public void markFailed(Long userId, Long userRepositoryId) {

        save(userId, userRepositoryId, true, List.of());

    }

    private void save(Long userId, Long userRepositoryId, boolean allFailed, List<RecommendedCompany> shown) {

        Instant now = Instant.now(clock);
        transaction.executeWithoutResult(status -> {
            Optional<RepoRecommendation> saved = recommendations.findWithLockByUserRepositoryId(userRepositoryId);
            if (saved.isPresent()) {
                if (allFailed) {
                    saved.get().fail(now);
                } else {
                    saved.get().refresh(shown, now);
                }
                return;
            }
            recommendations.save(allFailed ? RepoRecommendation.failed(userId, userRepositoryId, now)
                    : RepoRecommendation.ready(userId, userRepositoryId, shown, now));
        });

    }

    private record Scored(RecommendedCompany company, int shortlistRank) {
    }
}
