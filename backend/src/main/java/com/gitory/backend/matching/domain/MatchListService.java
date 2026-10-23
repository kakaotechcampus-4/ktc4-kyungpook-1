package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchableCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
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

    private final JobPostingRepository postings;
    private final MatchEvidenceSearch evidenceSearch;
    private final Clock clock;

    public List<MatchTarget> list(Long userId) {

        List<MatchableCondition> rows = postings.findMatchableConditions(Instant.now(clock));
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, List<MatchableCondition>> byPosting = rows.stream()
                .collect(Collectors.groupingBy(row -> row.posting().getId(), LinkedHashMap::new, Collectors.toList()));

        MatchEvidence evidence = evidenceSearch.find(userId);
        Set<Long> metIds = evidence.matches().stream().map(CriterionMatch::criterionId).collect(Collectors.toSet());

        List<MatchTarget> sorted = byPosting.values().stream()
                .map(conditions -> MatchTargets.of(conditions, metIds, evidence.confirmed().hasConfirmedCard()))
                .sorted(BY_FIT_THEN_DEADLINE)
                .toList();
        return mixCompanies(sorted);

    }

    /**
     * 한 회사의 공고가 위쪽을 덮지 않도록, 같은 등급 안에서 회사마다 한 건씩 돌아가며 놓는다
     * 등급을 넘어서는 섞지 않고, 회사 순서와 회사 안의 공고 순서는 들어온 정렬 순서를 따른다
     */
    static List<MatchTarget> mixCompanies(List<MatchTarget> sorted) {

        List<MatchTarget> mixed = new ArrayList<>(sorted.size());
        int start = 0;
        while (start < sorted.size()) {
            MatchGrade fit = sorted.get(start).fit();
            int end = start;
            while (end < sorted.size() && Objects.equals(sorted.get(end).fit(), fit)) {
                end++;
            }
            mixed.addAll(roundRobin(sorted.subList(start, end)));
            start = end;
        }
        return mixed;

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
