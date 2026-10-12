package com.gitory.backend.recommend.domain;

import com.gitory.backend.recommend.infra.CommitCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 커밋 찾기 결과가 어느 후보에 들어 있는지를 한 번에 찾는다
 * 사용자가 후보에서 뺀 커밋은 들어 있는 것으로 보지 않는다
 */
@Service
@RequiredArgsConstructor
public class CommitCandidateLookup {

    private final CommitCandidateRepository candidates;

    /** 후보에 든 커밋만 맵에 있고, 여러 후보에 든 커밋은 내부 id 가 가장 작은 후보를 돌려준다 */
    public Map<Long, UUID> byCommit(Long userRepositoryId, Collection<Long> commitIds) {

        if (commitIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, UUID> found = new HashMap<>();
        for (CommitCandidate row : candidates.findIncluding(userRepositoryId, commitIds)) {
            found.putIfAbsent(row.commitId(), row.candidatePublicId());
        }

        return found;

    }
}
