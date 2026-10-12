package com.gitory.backend.card.domain;

import com.gitory.backend.card.infra.MeCardStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 정보 화면에 쓸 카드 숫자를 card 표를 다른 모듈이 읽지 않고 얻게 한다 */
@Service
@RequiredArgsConstructor
public class MeCardStatsService {

    private final MeCardStatsRepository stats;

    @Transactional(readOnly = true)
    public int confirmedCards(Long userId) {

        return Math.toIntExact(stats.countConfirmed(userId));

    }

    @Transactional(readOnly = true)
    public int answeredTurns(Long userId) {

        return Math.toIntExact(stats.countAnsweredTurns(userId));

    }
}
