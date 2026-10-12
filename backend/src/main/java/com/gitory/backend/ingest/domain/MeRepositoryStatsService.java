package com.gitory.backend.ingest.domain;

import com.gitory.backend.ingest.infra.MeRepositoryStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 정보 화면에 쓸 저장소 숫자를 ingest 표를 다른 모듈이 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class MeRepositoryStatsService {

    private final MeRepositoryStatsRepository stats;

    @Transactional(readOnly = true)
    public MeRepositoryStats of(Long userId) {

        MeRepositoryTotals totals = stats.totalsOf(userId);

        return new MeRepositoryStats(stats.findIdsByUserId(userId), Math.toIntExact(totals.analyzedRepos()),
                totals.lastAnalyzedAt());

    }
}
