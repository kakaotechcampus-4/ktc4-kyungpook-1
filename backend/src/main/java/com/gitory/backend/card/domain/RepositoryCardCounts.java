package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.RepositoryCardCountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 저장소 목록에 보여 줄 확정한 카드 수를 저장소 연결마다 센다
 * 작성 중인 카드와 지운 카드는 세지 않는다
 */
@Service
@RequiredArgsConstructor
public class RepositoryCardCounts {

    private final RepositoryCardCountRepository counts;

    /** 확정한 카드가 없는 저장소는 맵에 넣지 않는다 */
    public Map<Long, Integer> confirmedByRepository(Collection<Long> userRepositoryIds) {

        if (userRepositoryIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> found = new HashMap<>();
        for (RepositoryCardCountRepository.Row row : counts.countConfirmed(userRepositoryIds)) {
            found.put(row.getUserRepositoryId(), row.getCardCount().intValue());
        }

        return found;

    }
}
