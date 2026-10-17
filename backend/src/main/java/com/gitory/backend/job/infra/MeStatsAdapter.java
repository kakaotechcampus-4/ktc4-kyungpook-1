package com.gitory.backend.job.infra;

import com.gitory.backend.card.domain.MeCardStatsService;
import com.gitory.backend.consent.port.MeStats;
import com.gitory.backend.consent.port.MeStatsPort;
import com.gitory.backend.ingest.domain.MeRepositoryStats;
import com.gitory.backend.ingest.domain.MeRepositoryStatsService;
import com.gitory.backend.recommend.domain.MeCandidateStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * consent 가 card·ingest·recommend 를 읽으면 모듈이 순환하므로, 모두를 쓸 수 있는 job 이 consent 의 포트를 채운다
 * 각 모듈은 그 모듈의 조회 메서드로만 읽는다
 */
@Component
@RequiredArgsConstructor
public class MeStatsAdapter implements MeStatsPort {

    private final MeCardStatsService cards;
    private final MeRepositoryStatsService repositories;
    private final MeCandidateStatsService candidates;

    @Override
    public MeStats of(Long userId) {

        MeRepositoryStats repos = repositories.of(userId);

        return new MeStats(cards.confirmedCards(userId), repos.analyzedRepos(), cards.answeredTurns(userId),
                candidates.newCandidates(repos.userRepositoryIds()), repos.lastAnalyzedAt());

    }
}
