package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.PostingCondition;
import com.gitory.backend.profile.domain.ProfileService;
import com.gitory.backend.profile.domain.ProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 매칭 1단계로, AI 없이 인적사항으로 공고를 거르고 카드 기술이 겹친 만큼 회사 순위를 매긴다
 * 같은 입력이면 늘 같은 순서가 나와야 해서, 점수가 같을 때 가르는 기준을 끝까지 둔다
 */
@Service
@RequiredArgsConstructor
public class CompanyShortlistService {

    // 인적사항의 달은 한국 달력 기준이다
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Comparator<CandidatePosting> BEST_POSTING_FIRST = Comparator
            .comparing(CandidatePosting::overlap, TechOverlap.STRONGER_FIRST)
            .thenComparing(candidate -> candidate.posting().getExpiresAt(),
                    Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(candidate -> candidate.posting().getId(),
                    Comparator.nullsLast(Comparator.<Long>naturalOrder()));
    private static final Comparator<ShortlistedPosting> BEST_SHORTLISTED_POSTING_FIRST = Comparator
            .comparing(ShortlistedPosting::overlap, TechOverlap.STRONGER_FIRST)
            .thenComparing(found -> found.posting().getId(), Comparator.nullsLast(Comparator.<Long>naturalOrder()));
    private static final Comparator<ShortlistedCompany> STRONGER_COMPANY_FIRST = Comparator
            .comparing(ShortlistedCompany::overlap, TechOverlap.STRONGER_FIRST)
            .thenComparing(ShortlistedCompany::postingsWithRequiredTech, Comparator.reverseOrder())
            .thenComparing(company -> company.representative().getExpiresAt(),
                    Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(ShortlistedCompany::companyId);

    private final JobPostingRepository postings;
    private final ProfileService profiles;
    private final CompanyDirectory companies;
    private final Clock clock;

    /**
     * techs 는 부르는 쪽이 고른 카드들의 기술 이름표이며, 겹친 기술이 하나도 없는 공고는 후보에 넣지 않는다
     * 관심 분야는 저장된 인적사항에서 읽어 그 직무의 공고만 남기고, 아직 고르지 않았으면 거르지 않는다
     */
    @Transactional(readOnly = true)
    public List<ShortlistedCompany> shortlist(Long userId, Set<CardTech> techs, int limit) {

        List<CandidatePosting> candidates = new ArrayList<>();
        for (ShortlistedPosting found : overlapping(userId, techs, posting -> true)) {
            JobPosting posting = found.posting();
            String companyId = companies.of(posting.getCompanyKey(), posting.getCompanyName()).id();
            candidates.add(new CandidatePosting(companyId, posting, found.overlap()));
        }
        return rank(candidates, limit);

    }

    /**
     * 회사로 묶지 않고 postingIds 에 든 공고만 같은 인적사항 거르기와 기술 겹침으로 남겨, 많이 겹친 공고부터 돌려준다
     * 새 공고 알림이 새로 조건을 뽑은 공고만 사용자마다 줄일 때 쓴다
     */
    @Transactional(readOnly = true)
    public List<ShortlistedPosting> postingsAmong(Long userId, Set<CardTech> techs, Set<Long> postingIds) {

        return overlapping(userId, techs, posting -> postingIds.contains(posting.getId())).stream()
                .sorted(BEST_SHORTLISTED_POSTING_FIRST)
                .toList();

    }

    private List<ShortlistedPosting> overlapping(Long userId, Set<CardTech> techs, Predicate<JobPosting> inScope) {

        Instant now = Instant.now(clock);
        ProfileView profile = profiles.of(userId);
        Set<JobCategory> interests = profile.interests().stream()
                .map(interest -> JobCategory.valueOf(interest.name()))
                .collect(Collectors.toSet());
        Applicant applicant = Applicant.of(profile, interests, YearMonth.from(now.atZone(SEOUL)));
        Set<String> mine = techs.stream().map(Enum::name).collect(Collectors.toSet());

        Map<UUID, List<PostingCondition>> byPosting = postings.findShortlistConditions(now).stream()
                .collect(Collectors.groupingBy(row -> row.posting().getPublicId(), LinkedHashMap::new,
                        Collectors.toList()));
        List<ShortlistedPosting> found = new ArrayList<>();
        for (List<PostingCondition> rows : byPosting.values()) {
            JobPosting posting = rows.getFirst().posting();
            if (!inScope.test(posting)) {
                continue;
            }
            List<JobPostingCriterion> criteria = rows.stream().map(PostingCondition::criterion).toList();
            if (!applicant.allows(posting, criteria)) {
                continue;
            }
            TechOverlap overlap = TechOverlap.of(posting, criteria, mine);
            if (!overlap.isEmpty()) {
                found.add(new ShortlistedPosting(posting, criteria, overlap));
            }
        }
        return found;

    }

    static List<ShortlistedCompany> rank(List<CandidatePosting> candidates, int limit) {

        return candidates.stream()
                .collect(Collectors.groupingBy(CandidatePosting::companyId))
                .entrySet().stream()
                .map(entry -> companyOf(entry.getKey(), entry.getValue()))
                .sorted(STRONGER_COMPANY_FIRST)
                .limit(limit)
                .toList();

    }

    // 회사는 가장 잘 맞는 공고 하나로 견줘, 안 맞는 공고가 많은 회사가 평균에 밀리거나 공고가 많은 회사가 앞서지 않게 한다
    private static ShortlistedCompany companyOf(String companyId, List<CandidatePosting> postings) {

        CandidatePosting best = postings.stream().min(BEST_POSTING_FIRST).orElseThrow();
        int withRequiredTech = (int) postings.stream()
                .filter(candidate -> !candidate.overlap().required().isEmpty())
                .count();
        return new ShortlistedCompany(companyId, best.posting().getCompanyName(), best.posting(), best.overlap(),
                withRequiredTech);

    }

    record CandidatePosting(String companyId, JobPosting posting, TechOverlap overlap) {
    }
}
