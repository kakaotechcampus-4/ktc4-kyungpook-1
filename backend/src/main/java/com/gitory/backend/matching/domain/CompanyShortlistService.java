package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.PostingCondition;
import com.gitory.backend.profile.domain.ProfileService;
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

    /** techs 는 부르는 쪽이 고른 카드들의 기술 이름표이며, 겹친 기술이 하나도 없는 공고는 후보에 넣지 않는다 */
    @Transactional(readOnly = true)
    public List<ShortlistedCompany> shortlist(Long userId, Set<CardTech> techs, Set<JobCategory> interests,
                                              int limit) {

        Instant now = Instant.now(clock);
        Applicant applicant = Applicant.of(profiles.of(userId), interests, YearMonth.from(now.atZone(SEOUL)));
        Set<String> mine = techs.stream().map(Enum::name).collect(Collectors.toSet());

        Map<UUID, List<PostingCondition>> byPosting = postings.findShortlistConditions(now).stream()
                .collect(Collectors.groupingBy(row -> row.posting().getPublicId(), LinkedHashMap::new,
                        Collectors.toList()));
        List<CandidatePosting> candidates = new ArrayList<>();
        for (List<PostingCondition> rows : byPosting.values()) {
            JobPosting posting = rows.getFirst().posting();
            List<JobPostingCriterion> criteria = rows.stream().map(PostingCondition::criterion).toList();
            if (!applicant.allows(posting, criteria)) {
                continue;
            }
            TechOverlap overlap = TechOverlap.of(posting, criteria, mine);
            if (!overlap.isEmpty()) {
                String companyId = companies.of(posting.getCompanyKey(), posting.getCompanyName()).id();
                candidates.add(new CandidatePosting(companyId, posting, overlap));
            }
        }
        return rank(candidates, limit);

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
