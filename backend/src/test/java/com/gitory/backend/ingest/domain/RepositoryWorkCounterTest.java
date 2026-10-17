package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.port.RepositoryCandidateCardPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositoryWorkCounterTest {

    @Test
    @DisplayName("경험 그룹화 시각을 후보 수보다 먼저 읽어, 그 사이 새로 끝난 경험 그룹화 결과가 가짜 확정 0 으로 보이지 않게 한다")
    void readsGroupedAtBeforeCandidateCount() {

        RepositoryCandidateCardPort port = mock(RepositoryCandidateCardPort.class);
        when(port.groupedAt(anyCollection())).thenReturn(Map.of());
        when(port.candidateCounts(anyCollection())).thenReturn(Map.of());
        when(port.cardCounts(anyCollection())).thenReturn(Map.of());

        new RepositoryWorkCounter(port).fill(List.of(summary()));

        InOrder order = inOrder(port);
        order.verify(port).groupedAt(anyCollection());
        order.verify(port).candidateCounts(anyCollection());

    }

    private static RepositorySummary summary() {

        return new RepositorySummary(1L, UUID.randomUUID(), "grow22", "gitory", "Java", null, null,
                Instant.parse("2026-10-08T05:00:00Z"), null, 0, 0, 0, 0, 0);

    }
}
