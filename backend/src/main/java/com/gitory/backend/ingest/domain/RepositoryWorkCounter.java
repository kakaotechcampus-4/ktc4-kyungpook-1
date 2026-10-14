package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.port.RepositoryCandidateCardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 저장소 목록과 상세가 같은 방식으로 후보·카드 수를 채우도록 한 곳에서 맡는다
 * 저장소가 몇 개든 후보 수·경험 그룹화 시각·카드 수 조회를 각각 한 번으로 채운다
 */
@Component
@RequiredArgsConstructor
public class RepositoryWorkCounter {

    private final RepositoryCandidateCardPort work;

    public List<RepositorySummary> fill(List<RepositorySummary> summaries) {

        List<Long> connectionIds = summaries.stream().map(RepositorySummary::connectionId).toList();
        Map<Long, Instant> groupedAt = work.groupedAt(connectionIds);
        Map<Long, Integer> candidates = work.candidateCounts(connectionIds);
        Map<Long, Integer> cards = work.cardCounts(connectionIds);

        return summaries.stream()
                .map(summary -> summary.withCounts(
                        candidates.getOrDefault(summary.connectionId(), 0),
                        groupedAt.get(summary.connectionId()),
                        cards.getOrDefault(summary.connectionId(), 0)))
                .toList();

    }
}
