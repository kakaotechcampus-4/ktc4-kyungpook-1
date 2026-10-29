package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchableCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 매칭 목록을 열 때마다 확정 카드 문장과 공고 조건을 AI 검색으로 맞춰 등급을 센다
 * 결과는 저장하지 않고, 검색 호출이 DB 트랜잭션을 붙잡지 않도록 이 서비스는 트랜잭션을 열지 않는다
 */
@Service
@RequiredArgsConstructor
public class MatchListService {

    private static final Comparator<MatchTarget> BY_FIT_THEN_DEADLINE = Comparator
            .comparing(MatchTarget::fit, Comparator.nullsLast(Comparator.<MatchGrade>naturalOrder()))
            .thenComparing(MatchTarget::expiresAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()));
    private static final Duration URGENT_WINDOW = Duration.ofDays(7);

    private final JobPostingRepository postings;
    private final MatchEvidenceSearch evidenceSearch;
    private final CompanyDirectory companies;
    private final Clock clock;

    /** companyId 가 null 이거나 비어 있으면 거르지 않고, 거른 뒤 남은 공고가 없으면 AI 를 부르지 않는다 */
    public List<MatchTarget> list(Long userId, String companyId) {

        Instant now = Instant.now(clock);
        boolean filtered = companyId != null && !companyId.isBlank();
        Map<Long, List<MatchableCondition>> byPosting = postings.findMatchableConditions(now).stream()
                .filter(row -> !filtered || companyId.equals(companyIdOf(row.posting())))
                .collect(Collectors.groupingBy(row -> row.posting().getId(), LinkedHashMap::new, Collectors.toList()));
        if (byPosting.isEmpty()) {
            return List.of();
        }

        MatchEvidence evidence = evidenceSearch.find(userId);
        Set<Long> metIds = evidence.matches().stream().map(CriterionMatch::criterionId).collect(Collectors.toSet());

        List<MatchTarget> sorted = byPosting.values().stream()
                .map(conditions -> MatchTargets.of(conditions, metIds, evidence.confirmed().hasConfirmedCard(), companies))
                .sorted(BY_FIT_THEN_DEADLINE)
                .toList();
        return mixCompanies(sorted, now);

    }

    private String companyIdOf(JobPosting posting) {

        return companies.of(posting.getCompanyKey(), posting.getCompanyName()).id();

    }

    /**
     * 한 회사의 공고가 위쪽을 덮지 않도록, 같은 등급 안에서 회사마다 한 건씩 돌아가며 놓는다
     * 마감이 가까운 공고는 섞다가 묻히지 않도록 등급마다 맨 앞에 마감순으로 두고, 등급을 넘어서는 섞지 않는다
     */
    static List<MatchTarget> mixCompanies(List<MatchTarget> sorted, Instant now) {

        Instant urgentUntil = now.plus(URGENT_WINDOW);

        List<MatchTarget> mixed = new ArrayList<>(sorted.size());
        int start = 0;
        while (start < sorted.size()) {
            MatchGrade fit = sorted.get(start).fit();
            int end = start;
            while (end < sorted.size() && Objects.equals(sorted.get(end).fit(), fit)) {
                end++;
            }
            Map<Boolean, List<MatchTarget>> byUrgency = sorted.subList(start, end).stream()
                    .collect(Collectors.partitioningBy(target -> isUrgent(target, urgentUntil)));
            mixed.addAll(byUrgency.get(true));
            mixed.addAll(roundRobin(byUrgency.get(false)));
            start = end;
        }
        return mixed;

    }

    private static boolean isUrgent(MatchTarget target, Instant urgentUntil) {

        return target.expiresAt() != null && !target.expiresAt().isAfter(urgentUntil);

    }

    private static List<MatchTarget> roundRobin(List<MatchTarget> sameFit) {

        Map<String, Deque<MatchTarget>> byCompany = new LinkedHashMap<>();
        for (MatchTarget target : sameFit) {
            byCompany.computeIfAbsent(target.company(), company -> new ArrayDeque<>()).add(target);
        }

        List<MatchTarget> mixed = new ArrayList<>(sameFit.size());
        while (mixed.size() < sameFit.size()) {
            for (Deque<MatchTarget> queue : byCompany.values()) {
                if (!queue.isEmpty()) {
                    mixed.add(queue.poll());
                }
            }
        }
        return mixed;

    }
}
