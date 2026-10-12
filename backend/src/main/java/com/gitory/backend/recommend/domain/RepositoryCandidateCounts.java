package com.gitory.backend.recommend.domain;

import com.gitory.backend.recommend.infra.CandidateGroupingRepository;
import com.gitory.backend.recommend.infra.RepositoryCandidateCountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 저장소 목록에 보여 줄 후보 수와 후보를 마지막으로 묶은 시각을 저장소 연결마다 읽는다
 * 후보 수는 상태와 종류를 가리지 않고 센다
 */
@Service
@RequiredArgsConstructor
public class RepositoryCandidateCounts {

    private final RepositoryCandidateCountRepository counts;
    private final CandidateGroupingRepository groupings;

    /** 후보가 없는 저장소는 맵에 넣지 않는다 */
    public Map<Long, Integer> byRepository(Collection<Long> userRepositoryIds) {

        if (userRepositoryIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> found = new HashMap<>();
        for (RepositoryCandidateCount row : counts.countByUserRepositoryIds(userRepositoryIds)) {
            found.put(row.userRepositoryId(), (int) row.count());
        }

        return found;

    }

    /** 한 번도 묶지 않은 저장소는 맵에 넣지 않는다 */
    public Map<Long, Instant> groupedAtByRepository(Collection<Long> userRepositoryIds) {

        Map<Long, Instant> found = new HashMap<>();
        for (CandidateGrouping grouping : groupings.findAllById(userRepositoryIds)) {
            found.put(grouping.getUserRepositoryId(), grouping.getGroupedAt());
        }

        return found;

    }
}
