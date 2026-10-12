package com.gitory.backend.job.infra;

import com.gitory.backend.card.domain.CandidateCardQueryService;
import com.gitory.backend.recommend.port.UsedCardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** recommend 와 card 는 서로를 모르므로, 둘을 엮는 job 에서 카드 모듈의 조회를 recommend 의 포트로 이어 준다 */
@Component
@RequiredArgsConstructor
public class UsedCardAdapter implements UsedCardPort {

    private final CandidateCardQueryService cards;

    @Override
    public Map<Long, UUID> cardIdsOf(Collection<Long> candidateIds) {

        return cards.publicIdsOf(candidateIds);

    }
}
