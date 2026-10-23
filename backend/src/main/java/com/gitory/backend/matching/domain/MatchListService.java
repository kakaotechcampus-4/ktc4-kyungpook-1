package com.gitory.backend.matching.domain;

import com.gitory.backend.agent.port.CriterionMatch;
import com.gitory.backend.matching.infra.JobPostingRepository;
import com.gitory.backend.matching.infra.MatchableCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        return byPosting.values().stream()
                .map(conditions -> MatchTargets.of(conditions, metIds, evidence.confirmed().hasConfirmedCard()))
                .sorted(BY_FIT_THEN_DEADLINE)
                .toList();

    }
}
