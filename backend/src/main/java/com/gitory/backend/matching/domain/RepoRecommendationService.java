package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import com.gitory.backend.card.domain.MatchingCardLabel;
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
 * 레포 하나의 작성 중·확정 카드로 추천 기업 3곳을 계산해 저장하며, 레포 화면은 이 저장본만 읽는다
 * AI 에게는 그 레포 카드와 기술이 겹친 조건만 물어, 레포가 공고 전체가 아니라 채운 조건만 말하게 한다
 * 계산하는 동안 후보 회사와 맞은 조건은 진행에 남겨, 화면이 고르는 과정을 보여 줄 수 있게 한다
 */
@Service
@RequiredArgsConstructor
public class RepoRecommendationService {

    static final int CANDIDATE_COMPANIES = 10;
    static final int SHOWN_COMPANIES = 3;
    static final int SHOWN_TECHS = 3;
    static final int LIVE_TECHS = 12;
    static final int LIVE_TEXT_LENGTH = 80;

    // 채운 필수 조건이 많은 회사가 먼저고, 같으면 채운 우대 조건, 그래도 같으면 1단계 순서다
    private static final Comparator<Scored> STRONGER_FIRST = Comparator
            .comparingInt((Scored scored) -> scored.company().requiredMet()).reversed()
            .thenComparing(Comparator.comparingInt((Scored scored) -> scored.company().preferredMet()).reversed())
            .thenComparingInt(Scored::shortlistRank);

    // 맞은 조건은 필수가 먼저고, 같은 종류 안에서는 공고에 적힌 순서다
    private static final Comparator<JobPostingCriterion> HIT_ORDER = Comparator
            .comparing(JobPostingCriterion::getType).thenComparingInt(JobPostingCriterion::getSeq);

    private final MatchingCardReader cards;
    private final CompanyShortlistService shortlist;
    private final JobPostingCriterionRepository criteria;
    private final MatchJudgementService judgements;
    private final RepoRecommendationRepository recommendations;
    private final CompanyDirectory companies;
    private final RepoRecommendationLiveLog liveLog;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** AI 를 부르는 동안 트랜잭션을 열지 않으며, 판단을 시도한 공고가 모두 실패했을 때만 실패로 저장한다 */
    public void compute(Long userId, Long userRepositoryId) {

        // 후보를 정하기 전에는 지난 계산의 끝난 진행이 이번 계산의 것처럼 보이지 않도록 먼저 지운다
        liveLog.drop(userRepositoryId);
        List<MatchingCard> repoCards = cards.allOf(userId, userRepositoryId).stream()
                .filter(RepoRecommendationService::hasText)
                .toList();
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
        List<List<JobPostingCriterion>> overlaps = candidates.stream()
                .map(company -> overlapping(byPosting.getOrDefault(company.representative().getId(), List.of()),
                        techNames))
                .toList();
        Map<Long, String> titles = titlesOf(repoCards);
        RepoRecommendationLiveLog.Live live = liveLog.start(userRepositoryId, repoCards.size(),
                techs.stream().map(CardTech::name).limit(LIVE_TECHS).toList(), waiting(candidates, overlaps));

        List<Scored> scored = new ArrayList<>();
        int asked = 0;
        int failed = 0;
        for (int rank = 0; rank < candidates.size(); rank++) {
            ShortlistedCompany company = candidates.get(rank);
            List<JobPostingCriterion> overlapping = overlaps.get(rank);
            if (overlapping.isEmpty()) {
                live.done(rank, List.of());
                continue;
            }
            asked++;
            live.checking(rank);
            Optional<Map<Long, List<JudgedSupport>>> judged =
                    judgements.judge(company.representative(), overlapping, repoCards);
            if (judged.isEmpty()) {
                failed++;
                live.failed(rank);
                continue;
            }
            live.done(rank, hits(overlapping, judged.get(), titles));
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
        save(userId, userRepositoryId, allFailed, shown, live.finishedSnapshot());
        live.finish();

    }

    // 초안을 쓰기 전 카드는 네 칸이 모두 비어 AI 에게 보낼 글이 없다
    private static boolean hasText(MatchingCard card) {

        return card.situation() != null || card.task() != null || card.action() != null || card.result() != null;

    }

    // 화면을 보는 사람은 카드 주인뿐이라 가리기 전 제목을 쓰고, 계산하는 사이 지워진 카드는 가린 제목으로 대신한다
    private Map<Long, String> titlesOf(List<MatchingCard> repoCards) {

        Map<Long, MatchingCardLabel> labels = cards.labelsOf(repoCards.stream().map(MatchingCard::id).toList());
        return repoCards.stream().collect(Collectors.toMap(MatchingCard::id,
                card -> labels.containsKey(card.id()) ? labels.get(card.id()).title() : card.title()));

    }

    private List<RepoRecommendationLive.Candidate> waiting(List<ShortlistedCompany> candidates,
                                                          List<List<JobPostingCriterion>> overlaps) {

        List<RepoRecommendationLive.Candidate> waiting = new ArrayList<>();
        for (int rank = 0; rank < candidates.size(); rank++) {
            ShortlistedCompany company = candidates.get(rank);
            JobPosting posting = company.representative();
            waiting.add(RepoRecommendationLive.Candidate.waiting(company.companyId(), company.companyName(),
                    companies.of(posting.getCompanyKey(), posting.getCompanyName()).logoUrl(),
                    new RepoRecommendationLive.Posting(posting.getPublicId(), posting.getTitle(),
                            posting.getExpiresAt()),
                    overlaps.get(rank).size()));
        }
        return waiting;

    }

    // 조건을 채운 카드는 id 순으로 오므로 제목은 그중 첫 카드의 것이다
    private static List<RepoRecommendationLive.Hit> hits(List<JobPostingCriterion> overlapping,
                                                        Map<Long, List<JudgedSupport>> judged,
                                                        Map<Long, String> titles) {

        return overlapping.stream()
                .filter(c -> !judged.getOrDefault(c.getId(), List.of()).isEmpty())
                .sorted(HIT_ORDER)
                .map(c -> new RepoRecommendationLive.Hit(c.getType(), shortened(c.getText()),
                        titles.get(judged.get(c.getId()).getFirst().cardId())))
                .toList();

    }

    private static String shortened(String text) {

        return text.length() <= LIVE_TEXT_LENGTH ? text : text.substring(0, LIVE_TEXT_LENGTH);

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

    /**
     * 계산이 예상하지 못한 이유로 멈췄을 때 부르며, 앞서 계산한 기업은 남긴다
     * 멈춘 계산의 진행은 지우는데, 조회가 FAILED 와 끝나지 않은 진행을 함께 읽지 않도록 저장보다 먼저 지운다
     */
    public void markFailed(Long userId, Long userRepositoryId) {

        liveLog.drop(userRepositoryId);
        save(userId, userRepositoryId, true, List.of(), null);

    }

    private void save(Long userId, Long userRepositoryId, boolean allFailed, List<RecommendedCompany> shown,
                      RepoRecommendationLive process) {

        Instant now = Instant.now(clock);
        transaction.executeWithoutResult(status -> {
            Optional<RepoRecommendation> saved = recommendations.findWithLockByUserRepositoryId(userRepositoryId);
            if (saved.isPresent()) {
                if (allFailed) {
                    saved.get().fail(now);
                } else {
                    saved.get().refresh(shown, process, now);
                }
                return;
            }
            recommendations.save(allFailed ? RepoRecommendation.failed(userId, userRepositoryId, now)
                    : RepoRecommendation.ready(userId, userRepositoryId, shown, process, now));
        });

    }

    private record Scored(RecommendedCompany company, int shortlistRank) {
    }
}
