package com.gitory.backend.job.infra;

import com.gitory.backend.card.domain.RepositoryCardCounts;
import com.gitory.backend.ingest.port.RepositoryCandidateCardPort;
import com.gitory.backend.recommend.domain.CommitCandidateLookup;
import com.gitory.backend.recommend.domain.RepositoryCandidateCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * ingest 가 아래 모듈이라 card·recommend 를 직접 부르지 않고, job.infra 가 두 모듈의 조회 메서드를 불러 인터페이스를 채운다
 */
@Component
@RequiredArgsConstructor
public class RepositoryCandidateCardAdapter implements RepositoryCandidateCardPort {

    private final RepositoryCandidateCounts candidateCounts;
    private final RepositoryCardCounts cardCounts;
    private final CommitCandidateLookup commitCandidates;

    @Override
    public Map<Long, Integer> candidateCounts(Collection<Long> connectionIds) {

        return candidateCounts.byRepository(connectionIds);

    }

    @Override
    public Map<Long, Instant> groupedAt(Collection<Long> connectionIds) {

        return candidateCounts.groupedAtByRepository(connectionIds);

    }

    @Override
    public Map<Long, Integer> cardCounts(Collection<Long> connectionIds) {

        return cardCounts.confirmedByRepository(connectionIds);

    }

    @Override
    public Map<Long, UUID> candidateIdsByCommit(Long connectionId, Collection<Long> commitIds) {

        return commitCandidates.byCommit(connectionId, commitIds);

    }
}
