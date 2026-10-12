package com.gitory.backend.recommend.domain;

import com.gitory.backend.recommend.infra.MeCandidateStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/** 저장소 연결은 recommend 가 읽지 않으므로, 부른 쪽이 사용자의 연결 id 를 넘겨 준다 */
@Service
@RequiredArgsConstructor
public class MeCandidateStatsService {

    private final MeCandidateStatsRepository stats;

    @Transactional(readOnly = true)
    public int newCandidates(Collection<Long> userRepositoryIds) {

        return Math.toIntExact(stats.countByUserRepositoryIdInAndStatus(userRepositoryIds, CandidateStatus.NEW));

    }
}
